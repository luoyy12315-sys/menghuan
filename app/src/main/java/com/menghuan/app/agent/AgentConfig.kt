package com.menghuan.app.agent

/**
 * 自动执行 Agent 的安全配置（存 SettingsRepository DataStore）。
 * 白名单语义：empty = 未启用白名单（自用放开所有应用）；非空 = 只允许操作清单内的包名
 * （本应用自身始终放行，用于回聊写入摘要）。
 */
data class AgentConfig(
    val maxSteps: Int = 15,
    val whitelist: Set<String> = emptySet(),
    val sensitiveConfirm: Boolean = true,
) {
    /** 白名单围栏：未启用或包名在清单内（本应用永远放行） */
    fun allowsPackage(packageName: String?): Boolean {
        if (packageName == null) return true
        if (packageName == "com.menghuan.app") return true
        if (whitelist.isEmpty()) return true
        return packageName in whitelist
    }
}
