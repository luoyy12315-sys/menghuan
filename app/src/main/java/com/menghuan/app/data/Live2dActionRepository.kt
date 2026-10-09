package com.menghuan.app.data

import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.CallActionBindingEntity
import com.menghuan.app.data.model.InteractionBindingEntity
import com.menghuan.app.data.model.Live2dActionMapper
import com.menghuan.app.live2d.ActionCodec
import com.menghuan.app.live2d.ActionSlot
import com.menghuan.app.live2d.ActionSource
import com.menghuan.app.live2d.BuiltinActions
import com.menghuan.app.live2d.InteractionBindings
import com.menghuan.app.live2d.InteractionRegion
import com.menghuan.app.live2d.Live2dAction
import com.menghuan.app.live2d.ModelActionCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 动作库仓库（v0.64.0）。
 *
 * 这一层是**引擎与数据库之间唯一的桥**：引擎（[com.menghuan.app.live2d.ActionPlayer]）
 * 完全不认识 Room，UI 也不直接碰 DAO。三条口径写在这里、不许在别处重复：
 *
 * 1. **内置动作每次启动对一遍**（[ensureBuiltins]）：内置动作的轨道定义在代码里，
 *    后续版本改幅度、改时长必须能生效；但**不能**覆盖掉用户在这行上点的收藏。
 * 2. **删除动作时把指向它的绑定一起收回来**：否则页面上会"绑着一条不存在的动作"，
 *    表现为角色卡着不动，而且没有任何提示（最难查的一类故障）。
 * 3. **绑定缺失 = 用该槽的内置默认**，永远不返回 null —— 用户从没进过设置页、
 *    或者内置动作那一行被清了，页面都必须还能正常工作。
 */
class Live2dActionRepository(private val db: AppDatabase) {

    private val dao = db.live2dActionDao()
    private val bindingDao = db.callActionBindingDao()
    private val interactionBindingDao = db.interactionBindingDao()

    /** 进程内只对一遍内置动作（并发进来也不重复写） */
    private val builtinLock = Mutex()

    @Volatile
    private var builtinsEnsured = false

    // ---- 内置动作播种 ----

    /**
     * 把内置动作对齐到代码里的最新定义。
     *
     * 对齐方式：**按 [Live2dAction.builtinKey] 找行**。缺则插入、在则只在内容变了时重写，
     * 并且**保留用户的收藏标记与原始建立时间**（收藏是用户在这个行上唯一的表达，
     * 被版本升级冲掉会让人觉得"我点的心白点了"）。
     */
    suspend fun ensureBuiltins(force: Boolean = false) {
        if (builtinsEnsured && !force) return
        builtinLock.withLock {
            if (builtinsEnsured && !force) return
            val now = System.currentTimeMillis()
            BuiltinActions.all().forEach { builtin ->
                val existing = dao.getByBuiltinKey(builtin.builtinKey)
                if (existing == null) {
                    dao.upsert(Live2dActionMapper.toEntity(builtin, now))
                } else {
                    val merged = builtin.copy(
                        id = existing.id,
                        favorite = existing.favorite,
                        createdAt = if (existing.createdAt > 0) existing.createdAt else now,
                    )
                    // 只在真的不一样时写，省掉每次启动的一批无意义写事务
                    if (ActionCodec.encode(merged) != existing.spec ||
                        merged.name != existing.name ||
                        merged.safeDurationMs.toLong() != existing.durationMs ||
                        merged.loop != existing.loop
                    ) {
                        dao.upsert(Live2dActionMapper.toEntity(merged, now))
                    }
                }
            }
            builtinsEnsured = true
        }
    }

    // ---- 读写动作 ----

    fun observe(personaId: Long): Flow<List<Live2dAction>> =
        dao.observeFor(personaId).map { rows -> rows.map(Live2dActionMapper::toAction) }

    suspend fun list(personaId: Long): List<Live2dAction> {
        ensureBuiltins()
        return dao.listFor(personaId).map(Live2dActionMapper::toAction)
    }

    suspend fun getById(id: Long): Live2dAction? {
        if (id <= 0L) return null
        return dao.getById(id)?.let(Live2dActionMapper::toAction)
    }

    suspend fun findBuiltin(builtinKey: String): Live2dAction? {
        if (builtinKey.isBlank()) return null
        ensureBuiltins()
        return dao.getByBuiltinKey(builtinKey)?.let(Live2dActionMapper::toAction)
            ?: BuiltinActions.byKey(builtinKey)
    }

    /** 新建 / 覆盖保存。返回库里那一行的 id（新建时是自增后的新 id） */
    suspend fun save(action: Live2dAction): Long {
        val now = System.currentTimeMillis()
        val entity = Live2dActionMapper.toEntity(action, now)
        return if (action.id > 0L) {
            dao.upsert(entity)
            action.id
        } else {
            dao.upsert(entity.copy(id = 0L))
        }
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) {
        if (id <= 0L) return
        dao.setFavorite(id, favorite, System.currentTimeMillis())
    }

    /**
     * 删除一条动作。**内置与模型自带的不让删**（返回 false）——
     * 内置动作是四态绑定的兜底，删掉就没有"默认"可回了。
     */
    suspend fun delete(id: Long): Boolean {
        if (id <= 0L) return false
        val action = getById(id) ?: return false
        if (!action.deletable) return false
        bindingDao.clearByAction(id)
        interactionBindingDao.clearByAction(id)
        dao.deleteById(id)
        return true
    }

    suspend fun deleteAll() {
        bindingDao.deleteAll()
        interactionBindingDao.deleteAll()
        dao.deleteAll()
        builtinsEnsured = false
    }

    // ---- 模型自带动作同步 ----

    /**
     * 把当前模型自带的动作组与表情同步进库（`source = MODEL`，归属该人设）。
     *
     * 判据是 [ModelActionCatalog.signature]：指纹没变就整个跳过 ——
     * 每次进角色空间都重写一遍的话，列表的 id 会变、绑定会断。
     */
    suspend fun syncModelActions(personaId: Long, catalog: ModelActionCatalog) {
        if (personaId <= 0L) return
        // 指纹 = 库里那批的键集合。键里带了组名与序号，所以"组数变了""换模型了"都会命中；
        // 键集合一致就整个跳过 —— 重写会让 id 变化、已存的动作绑定跟着断。
        val existingKeys = dao.listFor(personaId)
            .filter { it.source == ActionSource.MODEL.name && it.personaId == personaId }
            .map { it.builtinKey }
            .sorted()
        val targetKeys = modelActionKeys(catalog).sorted()
        if (existingKeys == targetKeys) return

        dao.deleteModelActions(personaId)
        val now = System.currentTimeMillis()
        val rows = ArrayList<com.menghuan.app.data.model.Live2dActionEntity>()

        catalog.motionGroups.forEach { (group, count) ->
            for (i in 0 until count) {
                val draft = Live2dAction(
                    name = if (count > 1) "$group ${i + 1}" else group,
                    durationMs = NATIVE_DEFAULT_DURATION_MS,
                    loop = group.equals("idle", ignoreCase = true),
                    motionGroup = group,
                    motionIndex = i,
                )
                rows.add(
                    Live2dActionMapper.toEntity(
                        draft.copy(source = ActionSource.MODEL, personaId = personaId, builtinKey = ModelActionCatalog.motionKey(group, i)),
                        now,
                    ),
                )
            }
        }
        catalog.expressions.forEach { name ->
            val draft = Live2dAction(
                name = name,
                type = com.menghuan.app.live2d.ActionType.EXPRESSION,
                durationMs = NATIVE_DEFAULT_DURATION_MS,
                expression = name,
            )
            rows.add(
                Live2dActionMapper.toEntity(
                    draft.copy(source = ActionSource.MODEL, personaId = personaId, builtinKey = ModelActionCatalog.expressionKey(name)),
                    now,
                ),
            )
        }
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }

    private fun modelActionKeys(catalog: ModelActionCatalog): List<String> =
        catalog.motionGroups.flatMap { (g, c) -> (0 until c).map { ModelActionCatalog.motionKey(g, it) } } +
            catalog.expressions.map { ModelActionCatalog.expressionKey(it) }

    /** 人设被删 / 换了模型：把这个角色的模型自带动作清掉（用户自己的动作不受影响） */
    suspend fun clearModelActions(personaId: Long) {
        if (personaId <= 0L) return
        dao.deleteModelActions(personaId)
        bindingDao.clearForPersona(personaId)
        // 互动绑定只可能指向"仍存在"的动作；模型动作被清后若绑定悬空，读时会兜回默认，
        // 这里顺手把整人设的互动绑定也收掉，免得库里留一行指向已删动作的死绑定。
        interactionBindingDao.clearForPersona(personaId)
    }

    // ---- 四态绑定 ----

    fun observeBindings(personaId: Long): Flow<Map<ActionSlot, Long>> =
        bindingDao.observeFor(personaId).map { rows -> toSlotMap(rows) }

    suspend fun bindings(personaId: Long): Map<ActionSlot, Long> =
        toSlotMap(bindingDao.getFor(personaId))

    private fun toSlotMap(rows: List<CallActionBindingEntity>): Map<ActionSlot, Long> {
        val out = LinkedHashMap<ActionSlot, Long>()
        rows.forEach { row -> ActionSlot.fromKey(row.slot)?.let { out[it] = row.actionId } }
        return out
    }

    /** 绑一条动作；[actionId] 传 0 = 恢复该槽的内置默认 */
    suspend fun setBinding(personaId: Long, slot: ActionSlot, actionId: Long) {
        if (personaId <= 0L) return
        ensureBuiltins()
        if (actionId <= 0L) {
            bindingDao.clearSlot(personaId, slot.key)
        } else {
            bindingDao.upsert(CallActionBindingEntity(personaId = personaId, slot = slot.key, actionId = actionId))
        }
    }

    /**
     * 解析某个状态槽"现在到底该播哪条动作"。**永不返回 null。**
     *
     * 顺序：绑定指定的那一行 → 该槽的内置默认（库里那行）→ 代码里的内置默认。
     * 第三档是"库被清过 / 播种还没跑完"时的最后兜底，保证通话页永远有动作可播。
     */
    suspend fun resolveSlot(personaId: Long, slot: ActionSlot): Live2dAction {
        ensureBuiltins()
        val bound = bindings(personaId)[slot] ?: 0L
        if (bound > 0L) getById(bound)?.let { return it }
        val key = BuiltinActions.defaultKeyOf(slot)
        return dao.getByBuiltinKey(key)?.let(Live2dActionMapper::toAction)
            ?: BuiltinActions.stateDefault(slot)
    }

    /** 四个槽一次全解出来（通话页只需要在状态切换时查一次，一次查完省三轮） */
    suspend fun resolveAllSlots(personaId: Long): Map<ActionSlot, Live2dAction> =
        ActionSlot.entries.associateWith { resolveSlot(personaId, it) }

    // ---- 点击互动 ----

    /**
     * 某个角色当前生效的互动绑定（v0.74.0）。
     *
     * 顺序：用户指定那一行 → 内置默认键。用户指定的那条动作**可能已被删掉**
     * （删动作时 [InteractionBindingDao.clearByAction] 会收掉绑定，但保险起见这里再查一次），
     * 查不到就退回内置默认 —— 保证 [InteractionBindings] 永远是一份"点了就有得播"的可用值。
     */
    suspend fun interactionBindings(personaId: Long): InteractionBindings {
        val rows = runCatching { interactionBindingDao.getFor(personaId) }.getOrDefault(emptyList())
        var headId = 0L
        var bodyId = 0L
        rows.forEach { row ->
            when (InteractionRegion.fromKey(row.region)) {
                InteractionRegion.HEAD -> headId = row.actionId
                InteractionRegion.BODY -> bodyId = row.actionId
                InteractionRegion.OTHER -> Unit
            }
        }
        // 指向的动作真的还在吗？不在就当没绑（与"删动作顺手清绑定"是同一条口径的第二道保险）
        if (headId > 0L && getById(headId) == null) headId = 0L
        if (bodyId > 0L && getById(bodyId) == null) bodyId = 0L
        return InteractionBindings(headActionId = headId, bodyActionId = bodyId)
    }

    /** 绑定 / 解绑一个互动区的动作；[actionId] 传 0 = 恢复该区默认 */
    suspend fun setInteractionBinding(personaId: Long, region: InteractionRegion, actionId: Long) {
        if (personaId <= 0L) return
        ensureBuiltins()
        if (actionId <= 0L) {
            interactionBindingDao.clearRegion(personaId, region.key)
        } else {
            interactionBindingDao.upsert(
                InteractionBindingEntity(personaId = personaId, region = region.key, actionId = actionId),
            )
        }
    }

    fun observeInteractionBindings(personaId: Long): Flow<Map<InteractionRegion, Long>> =
        interactionBindingDao.observeFor(personaId).map { rows ->
            val out = LinkedHashMap<InteractionRegion, Long>()
            rows.forEach { row -> InteractionRegion.fromKey(row.region).let { out[it] = row.actionId } }
            out
        }

    /**
     * 点到了某个区域该播哪条动作。
     *
     * @param seed 随机种子（实机传时间戳），让"点其他 = 随机趣味动作"可复现可测
     */
    suspend fun resolveInteraction(
        personaId: Long,
        region: InteractionRegion,
        seed: Long = System.currentTimeMillis(),
        bindings: InteractionBindings = InteractionBindings.DEFAULT,
    ): Live2dAction? {
        // 用户指定的那条优先（0 = 没指定）；指定了就播它，不去管内置默认键
        val chosen = bindings.actionIdOf(region)
        if (chosen > 0L) getById(chosen)?.let { return it }
        val key = bindings.builtinKeyOf(region)
            ?: return randomFunAction(personaId, bindings, seed)
        return findBuiltin(key)
    }

    /** 随机趣味动作池 = 内置趣味动作 + 用户/AI 建的非循环动作 */
    suspend fun randomFunAction(
        personaId: Long,
        bindings: InteractionBindings = InteractionBindings.DEFAULT,
        seed: Long = System.currentTimeMillis(),
    ): Live2dAction? {
        val all = list(personaId)
        val pool = all.filter { it.builtinKey in bindings.otherRandomKeys } +
            all.filter { (it.source == ActionSource.USER || it.source == ActionSource.AI) && !it.loop && it.hasTracks }
        if (pool.isEmpty()) return BuiltinActions.funActions().firstOrNull()
        val i = ((seed % pool.size) + pool.size).toInt() % pool.size
        return pool[i]
    }

    companion object {
        /** 模型自带动作的展示时长：真实时长由模型自己的 motion 决定，这个只用于列表显示 */
        const val NATIVE_DEFAULT_DURATION_MS = 2_000
    }
}
