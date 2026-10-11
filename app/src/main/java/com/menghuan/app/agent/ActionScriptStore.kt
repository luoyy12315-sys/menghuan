package com.menghuan.app.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 动作脚本缓存（v1.0 M3-H）：把**成功跑通**的任务存成「动作序列 + 每步元素指纹」，
 * 下次遇到相同目标 + 相同起始界面时本地重放 —— 重复任务从「15 次模型往返」降到 **0 次**。
 *
 * 铁律（方案 §6.2-H 明写）：
 * - 指纹用**包名 + resourceId/label**，**绝不用坐标**（坐标一改版就失效还容易点错）；
 * - 每一步重放前都做匹配校验，**不匹配立即放弃脚本、回退到逐屏问模型**，**不允许盲重放**；
 * - 只有**成功**的任务才落盘；失败任务不记录。
 *
 * 存储：`filesDir/agent_scripts.json`（纯文本，数据不出本机）。文件读写与匹配判定都是纯函数，
 * 便于 JVM 单测（生产调用方传 `filesDir` 下的 File）。
 */
data class ScriptStep(
    val action: String,          // tap / text / swipe / back / home
    val value: String = "",      // text 的输入内容
    val dir: String = "",        // swipe 方向
    /** 目标元素指纹（back/home/swipe 为 null） */
    val fp: StepFingerprint? = null,
)

/** 元素指纹：resourceId 优先，没有 id 的 App 靠 类型 + 标签 */
data class StepFingerprint(
    val viewId: String = "",
    val type: String = "",
    val label: String = "",
)

data class ActionScript(
    val goalKey: String,          // 规范化目标（[normalizeGoal]）
    val startPackage: String,     // 起始界面包名
    val startSignature: String,   // 起始界面指纹（[screenSignature]）
    val steps: List<ScriptStep>,
)

object ActionScriptStore {

    /** 脚本上限：够用即可，也不让文件无限长（超出后丢最旧的） */
    const val MAX_SCRIPTS = 50

    /** 短脚本没意义（1 步的任务重放价值低，还容易被"目标相同但界面已变"骗到） */
    const val MIN_STEPS = 2

    /** 起始界面指纹采样元素数 */
    private const val SIGNATURE_SAMPLES = 6

    /**
     * 目标规范化：只做**保守**处理（去首尾空白、压内部连续空白、拆掉结尾的句号）。
     * ⚠️ 刻意不做同义词/模糊匹配 —— 目标文案不同就不是同一个任务，重放错目标比慢更糟。
     */
    fun normalizeGoal(goal: String): String = goal
        .trim()
        .replace(Regex("\\s+"), " ")
        .trimEnd('。', '.', '！', '!')

    /** 起始界面指纹：包名 + 前几个元素的「类型|标签」串。顺序参与比较（同样的界面长什么样就算同一个起点） */
    fun screenSignature(packageName: String?, elements: List<UiElement>): String {
        val sample = elements.take(SIGNATURE_SAMPLES).joinToString(">") { "${it.type}|${it.label}" }
        return "${packageName.orEmpty()}#$sample"
    }

    /** 从元素生成指纹 */
    fun fingerprintOf(element: UiElement): StepFingerprint = StepFingerprint(
        viewId = element.viewId,
        type = element.type,
        label = element.label,
    )

    /**
     * 指纹匹配（纯函数）：
     * ① 有 resourceId → **必须** id 相同，且唯一命中（同一屏有多个相同 id = 不确定 → 放弃）；
     * ② 没 id → 类型 + 标签都相同，且唯一命中。
     * 命中不了、或命中多个，都返回 null（调用方据此放弃脚本、回退问模型）。
     */
    fun matchByFingerprint(fp: StepFingerprint?, elements: List<UiElement>): UiElement? {
        val fp0 = fp ?: return null
        val hits = when {
            fp0.viewId.isNotEmpty() -> elements.filter { it.viewId == fp0.viewId }
            else -> elements.filter { it.type == fp0.type && it.label == fp0.label }
        }
        return hits.singleOrNull()
    }

    /** 找出可重放的脚本：目标 + 起始包名 + 起始界面指纹三者全等 */
    fun find(
        scripts: List<ActionScript>,
        goal: String,
        packageName: String?,
        signature: String,
    ): ActionScript? {
        val key = normalizeGoal(goal)
        return scripts.lastOrNull {
            it.goalKey == key &&
                it.startPackage == packageName.orEmpty() &&
                it.startSignature == signature
        }
    }

    /** 落盘一条成功脚本（同目标+同起点覆盖旧的；超上限丢最旧的） */
    fun upsert(scripts: List<ActionScript>, script: ActionScript): List<ActionScript> {
        if (script.steps.size < MIN_STEPS) return scripts
        val kept = scripts.filterNot { it.goalKey == script.goalKey && it.startSignature == script.startSignature }
        return (kept + script).takeLast(MAX_SCRIPTS)
    }

    // ===== 文件读写（纯 JVM 可测） =====

    fun load(file: File): List<ActionScript> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { fromJson(arr.optJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(file: File, scripts: List<ActionScript>): Boolean = runCatching {
        val arr = JSONArray()
        scripts.forEach { arr.put(toJson(it)) }
        file.parentFile?.mkdirs()
        file.writeText(arr.toString())
        true
    }.getOrDefault(false)

    fun clear(file: File): Boolean = runCatching { file.delete() || !file.exists() }.getOrDefault(false)

    private fun toJson(s: ActionScript): JSONObject = JSONObject().apply {
        put("goal", s.goalKey)
        put("pkg", s.startPackage)
        put("sig", s.startSignature)
        put("steps", JSONArray().apply {
            s.steps.forEach { st ->
                put(JSONObject().apply {
                    put("action", st.action)
                    if (st.value.isNotEmpty()) put("value", st.value)
                    if (st.dir.isNotEmpty()) put("dir", st.dir)
                    st.fp?.let { fp ->
                        put("fp", JSONObject().apply {
                            if (fp.viewId.isNotEmpty()) put("viewId", fp.viewId)
                            if (fp.type.isNotEmpty()) put("type", fp.type)
                            if (fp.label.isNotEmpty()) put("label", fp.label)
                        })
                    }
                })
            }
        })
    }

    private fun fromJson(o: JSONObject?): ActionScript? {
        if (o == null) return null
        val goal = o.optString("goal").trim()
        if (goal.isEmpty()) return null
        val stepsJson = o.optJSONArray("steps") ?: return null
        val steps = (0 until stepsJson.length()).mapNotNull { i ->
            val st = stepsJson.optJSONObject(i) ?: return@mapNotNull null
            val action = st.optString("action").trim()
            if (action.isEmpty()) return@mapNotNull null
            val fpJson = st.optJSONObject("fp")
            ScriptStep(
                action = action,
                value = st.optString("value"),
                dir = st.optString("dir"),
                fp = fpJson?.let {
                    StepFingerprint(
                        viewId = it.optString("viewId"),
                        type = it.optString("type"),
                        label = it.optString("label"),
                    )
                },
            )
        }
        if (steps.isEmpty()) return null
        return ActionScript(
            goalKey = goal,
            startPackage = o.optString("pkg"),
            startSignature = o.optString("sig"),
            steps = steps,
        )
    }
}
