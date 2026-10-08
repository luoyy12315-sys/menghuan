package com.menghuan.app.ui.moment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.MomentMath
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
 * 动态页 VM（v0.73.0）。
 *
 * 只做四件事：把 `moments` 全量交给 [MomentMath] 排成时间线、写一条、翻一个赞、删一条。
 * 所有口径（帖子/回复、点赞串、相对时间、条数）都在纯函数那一侧，这里不落第二份 ——
 * 否则"列表说 3 条、点进去 2 条"这种不一致就是这么长出来的。
 */
class MomentsViewModel(
    private val db: AppDatabase,
    personas: kotlinx.coroutines.flow.Flow<List<Persona>>,
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

    /** 按 id 取一条详情页要的数据（帖子不存在 → null，页面据此返回上一级） */
    val moments: StateFlow<List<MomentEntry>> = db.momentDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 一次性提示（"已发布"/"已删除"），页面显示两秒后调 [clearMessage] */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun post(text: String) = publish(text, parentId = 0L)

    fun reply(parentId: Long, text: String) = publish(text, parentId)

    private fun publish(raw: String, parentId: Long) {
        val max = if (parentId == 0L) MomentMath.POST_MAX else MomentMath.REPLY_MAX
        val text = MomentMath.sanitize(raw, max)
        if (text.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                db.momentDao().upsert(MomentEntry(personaId = 0L, parentId = parentId, text = text))
            }
            _message.value = if (parentId == 0L) "已发布" else "已回复"
        }
    }

    /** 用户自己点赞（AI 侧的赞由 [MomentMath.USER_ID] 之外的 id 表示，本期不做） */
    fun toggleLike(id: Long) {
        viewModelScope.launch {
            val m = runCatching { db.momentDao().getById(id) }.getOrNull() ?: return@launch
            runCatching {
                db.momentDao().setLikedBy(id, MomentMath.toggleLike(m.likedBy, MomentMath.USER_ID))
            }
        }
    }

    /** 删一条帖子连带它的回复（[com.menghuan.app.data.db.MomentDao.deleteThread]） */
    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching { db.momentDao().deleteThread(id) }
            _message.value = "已删除"
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MenghuanApp
                MomentsViewModel(app.db, app.personaRepository.observePersonas())
            }
        }
    }
}
