package com.daoshi.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.daoshi.app.MainActivity
import com.daoshi.app.MentorApp
import com.daoshi.app.data.model.isEffectivelyDone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * 今日任务桌面小组件：黑底白字，最多 3 条（每日任务 + 今日到期基础任务，未完成在前），
 * 点击任意位置直达任务页（deep link daoshi://tasks）。
 * 数据刷新：系统周期 + App 启动 + 任务增删改勾选（TodayWidgetRefresher）。
 */
class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as MentorApp
        val tasks = app.db.taskDao().observeAll().first()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val todayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayEpochDay = today.toEpochDay()
        val todays = tasks
            .filter { it.repeatDaily || it.dueAt == 0L || it.dueAt in todayStart until todayEnd }
            .sortedBy { it.isEffectivelyDone(todayEpochDay) } // 未完成在前
            .take(3)

        provideContent {
            val titleStyle = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            val itemStyle = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp)
            val doneStyle = TextStyle(color = ColorProvider(Color(0xFF9E9E9E)), fontSize = 13.sp)
            val hintStyle = TextStyle(color = ColorProvider(Color(0xFF9E9E9E)), fontSize = 12.sp)

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .cornerRadius(16.dp)
                    .background(ColorProvider(Color(0xFF0B0B0B)))
                    .padding(12.dp),
            ) {
                Text(
                    "今日任务",
                    modifier = GlanceModifier.clickable(openTasks(context)),
                    style = titleStyle,
                )
                Spacer(modifier = GlanceModifier.height(6.dp))
                if (todays.isEmpty()) {
                    Text(
                        "暂无任务",
                        modifier = GlanceModifier.clickable(openTasks(context)),
                        style = hintStyle,
                    )
                } else {
                    todays.forEach { task ->
                        val done = task.isEffectivelyDone(todayEpochDay)
                        Row(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable(openTasks(context)),
                            verticalAlignment = Alignment.Vertical.CenterVertically,
                        ) {
                            Text(
                                if (done) "✓" else "○",
                                style = itemStyle,
                            )
                            Spacer(modifier = GlanceModifier.width(8.dp))
                            Text(
                                task.title,
                                style = if (done) doneStyle else itemStyle,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openTasks(context: Context) = actionStartActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse("daoshi://tasks"), context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    )
}

/** 任务数据变化的刷新入口：App 启动 / 任务增删改勾选后调用（未放置小组件时为无害空转） */
object TodayWidgetRefresher {
    fun refresh(context: Context) {
        val app = context.applicationContext as? MentorApp ?: return
        app.applicationScope.launch {
            runCatching { TodayWidget().updateAll(context.applicationContext) }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayWidget()
}
