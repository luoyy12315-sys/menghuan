package com.menghuan.app.data

/**
 * 动态的「位置」候选（v0.76.0）：**纯文字，不做任何定位**。
 *
 * ## 为什么不接 GPS
 * 一是权限：定位要 ACCESS_COARSE/FINE_LOCATION，为一个"发动态顺便带个地名"的功能
 * 弹权限窗不划算；二是逆地理——Android 的 Geocoder 依赖 Google 服务，
 * 大陆机器上绝大多数情况是 `getFromLocation` 直接返回空列表，
 * 于是屏幕上只剩一串经纬度（"30.2741, 120.1551"），比什么都不显示更糟。
 * 而用户本来要的就是"西湖边的星巴克"这种**人话地名**，手写最准。
 *
 * 所以这里的形状是：一批预置候选 + 用户发过的地方自动进候选（存在 SettingsRepository）。
 */
object MomentPlaces {

    /** 预置候选：发动态的人最常用到的那几类地点 */
    val presets: List<String> = listOf(
        "家", "公司", "咖啡馆", "健身房", "公园", "地铁",
    )

    /**
     * 候选列表的实际顺序：**最近用过的在前**，预置补在后面，去重、去掉空白。
     * 不看这一段的高频与否，一条地址最多出现一次（不要把历史列表做成"家、家、家"）。
     */
    fun candidates(recent: List<String>): List<String> =
        (recent.map { it.trim() }.filter { it.isNotEmpty() } + presets)
            .distinct()

    /**
     * 记一个地方：提到最前、去重、最多留 [MAX_RECENT] 条。
     *
     * 为什么要有上限：最常用的那几个地名总共也就几个，留 20 条只会让候选区变成
     * 一条需要横滑很久才能找到"家"的长尾巴。
     */
    fun remembered(recent: List<String>, place: String): List<String> {
        val clean = place.trim()
        if (clean.isEmpty()) return recent
        return (listOf(clean) + recent.map { it.trim() }.filter { it.isNotEmpty() })
            .distinct()
            .take(MAX_RECENT)
    }

    const val MAX_RECENT = 8
}
