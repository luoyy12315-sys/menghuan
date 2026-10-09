package com.menghuan.app.ui.moment

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.MomentImageStore
import com.menghuan.app.data.MomentMath
import com.menghuan.app.data.SettingsRepository
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.MomentEntry
import com.menghuan.app.data.model.Persona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 时间线的一份可渲染数据：排好的 thread + 作者表 + **是否已经加载过一次**。
 *
 * `loaded` 不是多余的字段：会话列表 v0.43.1 的教训是"空列表初值"会被 UI 当成
 * "确实没有内容"，冷启动先闪一下空态再换成列表。这里从同一个 `combine` 派生，
 * **不另开探测协程**（那条路会把 Room 查询线程争抢出来，还会让 runTest 挂住）。
 */
data class MomentFeed(
    val threads: List<MomentMath.Thread>,
    val authors: Map<Long, Persona>,
    val loaded: Boolean,
)

/**
 * 时间线**当前这一屏**（[MomentsScreen] 只看它）：已翻出来的那几页 + 还有没有下一页。
 *
 * 与 [MomentFeed] 分开是为了两个页面各取所需：详情页要的是**完整** thread
 * （哪怕它在列表里还没被翻到），列表页只关心"现在该显示到第几条"。
 */
data class MomentPage(
    val threads: List<MomentMath.Thread>,
    /** 抬头要用到的作者表（头像/名字），与帖子一起按页派生 */
    val authors: Map<Long, Persona>,
    val loaded: Boolean,
    /** 库里还有没翻出来的（据此显示"上拉加载"那条尾部探针） */
    val hasMore: Boolean,
    val loadingMore: Boolean,
)

/**
 * 动态页 VM（v0.76.0）。
 *
 * 五件事：把 `moments` 全量交给 [MomentMath] 排成时间线、按页喂给列表、写一条（可带图与位置）、
 * 翻一个赞、删一条。所有口径（帖子/回复、点赞串、相对时间、条数）都在纯函数那一侧，
 * 这里不落第二份 —— 否则"列表说 3 条、点进去 2 条"这种不一致就是这么长出来的。
 *
 * ## 为什么现在是分页的
 * v0.73.0 起是全量读 `moments`：动态本来就存在本机，几十条时无所谓。
 * v0.76.0 加了图，一条九宫格 = 9 次解码，**不分页的列表翻到老动态时会明显掉帧**，
 * 而 Room 的 Flow 每变一次都会把全量重新 emit 一遍。
 * 所以改成"先翻 12 条，滑到底再要下一页"（[PAGE_SIZE]）—— **DAO 一行没动**，
 * 分页只发生在派生层，时间线怎么排仍是 [MomentMath] 那一套。
 */
class MomentsViewModel(
    private val db: AppDatabase,
    personas: kotlinx.coroutines.flow.Flow<List<Persona>>,
    /** 发动态要存图、选完又丢弃时要删图，所以要有 Context */
    private val appContext: Context,
    private val settings: SettingsRepository,
) : ViewModel() {

    /** 详情页也要用同一份 thread 口径，所以直接吐 feed，页面不再自己排 */
    val feed: StateFlow<MomentFeed> =
        combine(db.momentDao().observeAll(), personas) { list, people ->
            MomentFeed(
                threads = MomentMath.threads(list),
                authors = people.associateBy { it.id },
                loaded = true,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            MomentFeed(emptyList(), emptyMap(), loaded = false),
        )

    /** 位置候选（最近用过的 + 预置） */
    val placeSuggestions: StateFlow<List<String>> = settings.momentPlaces
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoPlacesYet)

    /** 已经翻出来的条数（<= feed 的总数） */
    private val _visibleCount = MutableStateFlow(PAGE_SIZE)
    private val _loadingMore = MutableStateFlow(false)
    private val _refreshing = MutableStateFlow(false)

    /**
     * 刷新轮次：**下拉一次 +1**，列表页用它重播入场动画。
     *
     * 为什么不直接用一个"要不要重播"的布尔：错峰入场是"首次组合时跑一次"的写法
     * （[com.menghuan.app.ui.common.staggerEnter]），想再跑一次只能让 key 变化，
     * 把整组内容重新挂载一遍。
     */
    private val _round = MutableStateFlow(0)
    val round: StateFlow<Int> = _round.asStateFlow()

    val page: StateFlow<MomentPage> = combine(
        feed,
        _visibleCount,
        _loadingMore,
    ) { f, count, loading ->
        MomentPage(
            threads = f.threads.take(count),
            authors = f.authors,
            loaded = f.loaded,
            hasMore = f.threads.size > count,
            loadingMore = loading,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MomentPage(emptyList(), emptyMap(), loaded = false, hasMore = false, loadingMore = false),
    )

    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // ===== 翻页 / 刷新 =====

    /**
     * 滑到尾部要下一页。
     *
     * 这里先停 220ms 再扩一页：这段时间让尾部那条"正在加载"的过渡显示出来，
     * 否则本地分页是**瞬时**完成的，用户会以为自己没触发到。
     * 顺带这也挡住了"一次滑动连着触发好几次"（加载中有闸，见函数第一行）。
     */
    fun loadMore() {
        if (_loadingMore.value || !page.value.hasMore) return
        viewModelScope.launch {
            _loadingMore.value = true
            kotlinx.coroutines.delay(220)
            _visibleCount.value += PAGE_SIZE
            _loadingMore.value = false
        }
    }

    /**
     * 下拉刷新。本地库没有"服务器"可刷，所以这里做的是**回到第一页 + 重播入场动画**
     * （时间线本身由 Room Flow 驱动，本来就是最新的）。
     *
     * 最短 420ms 是给指示器留出**看得见**的一瞬：本地重排是毫秒级的，
     * 真的一闪就收，人会觉得"是不是没反应"。
     */
    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            kotlinx.coroutines.delay(420)
            _visibleCount.value = PAGE_SIZE
            _loadingMore.value = false
            _round.value++
            _refreshing.value = false
        }
    }

    // ===== 写 =====

    /** 从相册导入一张图到 App 私有目录，返回可落库的绝对路径（失败返回 null） */
    fun importImage(uri: Uri): String? = MomentImageStore.import(appContext, uri)

    fun post(text: String, images: List<String> = emptyList(), location: String = "") =
        publish(text, parentId = 0L, images = images, location = location)

    fun reply(parentId: Long, text: String) = publish(text, parentId, emptyList(), "")

    private fun publish(raw: String, parentId: Long, images: List<String>, location: String) {
        val max = if (parentId == 0L) MomentMath.POST_MAX else MomentMath.REPLY_MAX
        val text = MomentMath.sanitize(raw, max)
        // 回复不带图、不带位置：朋友圈这一层楼本来就是"说一句话"，给它配图只会把详情页拉长
        val packedImages = if (parentId == 0L) MomentMath.encodeImages(images) else ""
        val place = if (parentId == 0L) MomentMath.sanitizeLocation(location) else ""
        if (text.isEmpty() && packedImages.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                db.momentDao().upsert(
                    MomentEntry(
                        personaId = 0L,
                        parentId = parentId,
                        text = text,
                        images = packedImages,
                        location = place,
                    ),
                )
            }
            if (parentId == 0L && place.isNotBlank()) {
                runCatching { settings.rememberMomentPlace(place) }
            }
            _message.value = if (parentId == 0L) "已发布" else "已回复"
        }
    }

    /**
     * 用户自己点赞 / 取消。
     *
     * AI 侧的赞（v0.77.0）由 [MomentMath.USER_ID] 之外的 id 表示 —— 每个角色点赞时，
     * `likedBy` 里存它自己的 **personaId 字符串**，与用户的 `"u"` 分开，
     * 所以这条帖子在数据上分得清"谁赞过"。AI 的赞由 [ChatViewModel.maybePostMoments]
     * 在回复链路里落库；这里只动用户本人那一个 id（用户点不了别人的赞）。
     */
    fun toggleLike(id: Long) {
        viewModelScope.launch {
            val m = runCatching { db.momentDao().getById(id) }.getOrNull() ?: return@launch
            runCatching {
                db.momentDao().setLikedBy(id, MomentMath.toggleLike(m.likedBy, MomentMath.USER_ID))
            }
        }
    }

    /**
     * 删一条帖子连带它的回复（[com.menghuan.app.data.db.MomentDao.deleteThread]），
     * 顺手把它的图从磁盘删掉 —— 否则用户删了动态，图片还占着几十 MB，没人看得见也删不掉。
     */
    fun delete(id: Long) {
        viewModelScope.launch {
            val m = runCatching { db.momentDao().getById(id) }.getOrNull()
            runCatching { db.momentDao().deleteThread(id) }
            if (m != null) MomentImageStore.deleteAll(appContext, MomentMath.decodeImages(m.images))
            _message.value = "已删除"
        }
    }

    /** 一次性提示（"已发布"/"已删除"），页面显示两秒后调 [clearMessage] */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        /** 一页几条：不用滑第一下就到头，又不会让一屏里出现几十次解码 */
        const val PAGE_SIZE = 12

        private val NoPlacesYet: List<String> = com.menghuan.app.data.MomentPlaces.presets

        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MenghuanApp
                MomentsViewModel(
                    db = app.db,
                    personas = app.personaRepository.observePersonas(),
                    appContext = app.applicationContext,
                    settings = app.settingsRepository,
                )
            }
        }
    }
}
