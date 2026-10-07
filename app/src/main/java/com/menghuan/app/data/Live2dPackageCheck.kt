package com.menghuan.app.data

import org.json.JSONObject

/**
 * Live2D 模型包的结构校验（v0.58.0，纯函数，JVM 单测）。
 *
 * 用户上传的 `.model3.json` + `.moc3` + 贴图 + 动作 **是不可信输入**（方案文档 §十 / §二十九）：
 * 包可能缺文件、JSON 可能写坏、引用可能指向 `../../` 外面。这些都必须在导入阶段判掉，
 * 而不是等通话页加载模型时崩给用户看。
 *
 * 这里只做"结构 + 引用完整性"的判断，输入是**已经在私有目录里解压好的相对路径集合**，
 * 所以不碰任何 Android API，能纯 JVM 跑。真正的解压与落盘在 [CallVisualStore]。
 */
object Live2dPackageCheck {

    /** 校验通过：给出入口 model3.json 的相对路径与包内条目数 */
    data class Ok(val modelJsonPath: String, val entryCount: Int) : Result
    /** 校验不通过：[reason] 直接可以显示给用户 */
    data class Invalid(val reason: String) : Result

    sealed interface Result

    /** 资源包里的噪音条目：macOS 打包带出来的目录、隐藏文件 */
    private fun String.isNoise(): Boolean =
        startsWith("__MACOSX/") || startsWith(".") || contains("/.")

    /**
     * 在解压结果里找入口 `.model3.json`。
     *
     * 一个包里可能有多个模型（官方示例包就这样），取**路径最浅**的那个：
     * 用户自己打包时"根目录那个"就是他想要的那个，子目录里的通常是附带素材。
     * 同名深浅一致时按路径字典序取第一个，保证同一包两次导入结果一致。
     */
    fun locateModelJson(paths: List<String>): String? =
        paths.filter { !it.isNoise() }
            .filter { it.endsWith(".model3.json", ignoreCase = true) }
            .minWithOrNull(
                compareBy<String> { it.count { c -> c == '/' } }.thenBy { it },
            )

    /**
     * 相对路径解析：`FileReferences.Moc` 里写的是 `../Hiyori.moc3` 还是 `textures/a.png`，
     * 都是**相对于 model3.json 自己所在目录**的。这里把它折算成包内的规范相对路径，
     * 任何跑出包根（出现 `..` 越界）或带盘符的写法一律判非法。
     */
    fun resolveRelative(modelJsonPath: String, reference: String?): String? {
        val ref = reference?.replace('\\', '/')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (ref.contains(':') || ref.startsWith("/")) return null
        val base = modelJsonPath.substringBeforeLast('/', "")
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
            .toMutableList()
        for (part in ref.split('/')) {
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (base.isEmpty()) return null else base.removeAt(base.size - 1)
                else -> base.add(part)
            }
        }
        return base.joinToString("/").takeIf { it.isNotEmpty() }
    }

    /**
     * 完整校验：JSON 能否解析、`.moc3` 在不在、贴图/动作/表情/物理的引用是否都能落地。
     *
     * 官方格式里 Motions 是 `{"Idle":[{"File":"motions/idle.motion3.json"}]}` 这种
     * "组名 → 数组"的表，Expressions 是 `[{"Name":"smile","File":"expressions/smile.cdi3.json"}]`。
     * 这两处的嵌套形状不同，逐个取 File。
     *
     * 动作/表情/物理**缺了不算致命**（模型照样能显示与动嘴，方案文档 §二十四要求降级而非报错），
     * 所以只对"入口、moc、贴图"三项严格，其余只在**引用了但文件不存在**时才计入问题清单。
     */
    fun validate(modelJsonPath: String, modelJsonText: String?, available: Set<String>): Result {
        val text = modelJsonText?.takeIf { it.isNotBlank() }
            ?: return Invalid("没有找到 .model3.json 的内容")
        val root = runCatching { JSONObject(text) }.getOrElse {
            return Invalid(".model3.json 不是合法 JSON，模型包可能已损坏")
        }
        val refs = root.optJSONObject("FileReferences")
            ?: return Invalid(".model3.json 缺少 FileReferences 段")

        val moc = resolveRelative(modelJsonPath, refs.optString("Moc", ""))
            ?: return Invalid(".model3.json 里没有可用的 .moc3 引用")
        if (moc !in available) return Invalid("模型包缺少 $moc（核心模型文件）")

        val textures = ArrayList<String>()
        refs.optJSONArray("Textures")?.let { a ->
            for (i in 0 until a.length()) {
                val t = resolveRelative(modelJsonPath, a.optString(i, ""))
                    ?: return Invalid("贴图引用写法有问题")
                if (t !in available) return Invalid("模型包缺少贴图 $t")
                textures.add(t)
            }
        }
        if (textures.isEmpty()) return Invalid("模型包没有任何贴图引用，角色无法显示")

        // 可选段落：引用了就必须存在，没引用则跳过
        val optional = ArrayList<Pair<String, String>>()
        refs.optString("Physics", "").takeIf { it.isNotBlank() }?.let {
            optional += "物理摆动" to it
        }
        refs.optJSONArray("Expressions")?.let { a ->
            for (i in 0 until a.length()) {
                val f = a.optJSONObject(i)?.optString("File").orEmpty()
                if (f.isNotBlank()) optional += "表情" to f
            }
        }
        refs.optJSONObject("Motions")?.let { groups ->
            val keys = groups.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = groups.optJSONArray(k) ?: continue
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i)?.optString("File").orEmpty()
                    if (f.isNotBlank()) optional += "动作" to f
                }
            }
        }
        for ((what, raw) in optional) {
            val p = resolveRelative(modelJsonPath, raw)
                ?: return Invalid("${what}的引用路径越出了模型包范围")
            if (p !in available) return Invalid("模型包缺少${what}文件 $p")
        }
        return Ok(modelJsonPath, available.size)
    }
}
