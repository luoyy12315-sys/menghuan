package com.menghuan.app.ui.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextSecondary

/**
 * 记账分类（v0.83.0，用户点名要回来：餐饮 / 购物 / 娱乐 / 交通 …）。
 *
 * 库里只存**名字**（[com.menghuan.app.data.model.LedgerEntry.category]）；
 * ⚠️ v0.89.0（用户点名）**去掉了 emoji** —— 名单只留名字，选择器与流水行都只显示文字。
 *
 * 名单第一项是**未分类**（`name = ""`）——它是默认值：新记一笔默认选中它，
 * 也能随时点回来把分类清掉。没有它的话，编辑一笔没有分类的旧流水会被强制安一个分类上去。
 */
object LedgerCategory {
    data class Preset(val name: String) {
        /** 选择器 chip 与流水行里显示的文字 */
        val label: String get() = if (name.isEmpty()) "未分类" else name
    }

    val PRESETS: List<Preset> = listOf(
        Preset(""),
        Preset("餐饮"),
        Preset("购物"),
        Preset("娱乐"),
        Preset("交通"),
        Preset("居住"),
        Preset("医疗"),
        Preset("学习"),
        Preset("旅行"),
        Preset("人情"),
        Preset("工资"),
        Preset("理财"),
        Preset("其他"),
    )

    /** 分类名 → 名单下标（未分类与认不出的名字都落到 0 = 未分类） */
    fun indexOf(name: String): Int =
        PRESETS.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: 0

    /** 分类名 → 显示用文字（未分类就是「未分类」）。v0.89.0：不再带 emoji */
    fun labelOf(name: String): String = PRESETS.getOrNull(indexOf(name))?.label ?: "未分类"
}

/**
 * 分类点选器（v0.91.0，**推翻 v0.83.0 的滚轮**）。
 *
 * 用户点名：**不要滚动、不要中间那个焦点槽** —— 就排成一行紧凑的 chips，
 * **点哪个哪个就是选中**（选中态挂在被点的那一个自己身上，而不是"滚进槽里的那一个"）。
 *
 * 所以这里没有任何布局测量、没有 `Lazy`、没有吸附：就是一条 `Row`，
 * 窄屏放不下时整条**横向可滚**（普通列表手感、不吸附）。
 * 名单固定 13 项，一屏大约能放下 6 项，滚一下就能看全，点选靠的是点击不是把格子拨到中间。
 */
@Composable
fun LedgerCategoryPicker(
    /** 当前分类（空串 = 未分类） */
    category: String,
    /** 点了某一个 chip */
    onCategoryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Row(
        modifier
            .fillMaxWidth()
            // 横向可滚：内容超出时滑动查看；不吸附、不循环、无棘轮震动
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LedgerCategory.PRESETS.forEach { preset ->
            val selected = preset.name == category
            Text(
                preset.label,
                modifier = Modifier
                    // 内边距撑出胶囊形，比 NeuChip 更矮更紧凑（一行塞得下更多）
                    .background(
                        // 选中用全应用统一的选择色，未选是轨道灰——与 NeuChip 的选中语义同源
                        if (selected) NeuAccent else NeuTrack,
                        RoundedCornerShape(999.dp),
                    )
                    // 无 ripple，与全应用弹层同一约定
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onCategoryChange(preset.name) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                color = if (selected) AccentInk else TextSecondary,
            )
        }
    }
}
