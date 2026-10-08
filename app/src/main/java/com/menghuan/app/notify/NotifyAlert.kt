package com.menghuan.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.VibrationEffect

/**
 * 消息类通知的「响铃 / 勿扰时只震动」分流（v0.62.0，用户点名）。
 *
 * ⚠️ **为什么是两个渠道 id**，而不是"发通知那一刻改声音"：
 * Android 8 起渠道的声音与震动**创建后就锁死**（连 App 自己都不能改，只有用户能在系统里改），
 * 唯一的办法是预先建好两条、发的时候挑一条走。所以"勿扰"那条是 `<原id>_quiet`。
 *
 * ⚠️ **诚实边界**：`currentInterruptionFilter` 能**读到**系统是否勿扰（不需要任何权限），
 * 我们据此把这条通知提交成"不响铃 + 按震动模式"。但**勿扰下允不允许震动是 ROM 说的算**
 * （MIUI 的勿扰可以连震动一起压掉）。要连震动都保证，得用户手动给本应用开
 * 「勿扰访问权限」（`ACCESS_NOTIFICATION_POLICY`）再 `setBypassDnd` ——
 * 那个开关藏得极深、普通人根本找不到，v0.62.0 与用户确认后**没有走这条路**。
 *
 * AI 来电**不在这里降级**：打电话就是要叫得人，勿扰下也响（见 `CallRingtone` 的闹钟音轨）。
 */
object NotifyAlert {

    /** 勿扰版渠道 id 的后缀（与正常那条并存，互不覆盖） */
    private const val QUIET_SUFFIX = "_quiet"

    /** 系统勿扰是否开着（仅闹钟 / 仅优先 / 总静音都算勿扰中） */
    fun dndActive(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }.getOrDefault(false)

    /**
     * 建好这一路通知需要的渠道，返回**本次该用的渠道 id**。
     *
     * 正常态 → 默认通知音 + 震动；勿扰态 → 无声音 + 震动。
     * 两条都是幂等创建（`createNotificationChannel` 对同 id 是更新语义，重复调无害）。
     *
     * ⚠️ **`legacyIds` 不是可选的清理，而是这条需求能不能生效的关键**：
     * Android 8 起渠道的声音/震动**创建即锁死**，`createNotificationChannel` 对已经存在的
     * 同 id 渠道**不会改动这些设置**（用户的改动更是永远盖着 App）。
     * 所以"老装机上把提示音补上"只有一条路 —— **换一个新 id 重建**，
     * 再把没人用的旧 id 删掉，否则用户的系统通知设置里会越堆越多僵尸渠道。
     */
    fun channel(
        context: Context,
        id: String,
        name: String,
        description: String,
        importance: Int = NotificationManager.IMPORTANCE_DEFAULT,
        legacyIds: List<String> = emptyList(),
    ): String {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return id
        dropLegacy(manager, legacyIds)
        val dnd = dndActive(context)
        val targetId = if (dnd) id + QUIET_SUFFIX else id
        val channel = NotificationChannel(targetId, if (dnd) "$name（勿扰：仅震动）" else name, importance).apply {
            this.description = description
            // 勿扰那条**不给声音**——渠道一旦带声音，即便系统不在勿扰也会被 ROM 的
            // "铃声与振动"档推着响；显式 null 才是"这条只震不响"。
            setSound(
                if (dnd) null else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                if (dnd) null else notificationAudioAttributes(),
            )
            enableVibration(true)
            // "短-停-长"两下：比系统默认那一记"咚"更像"有人在找你"，又不至于吵。
            // ⚠️ android.jar 里这个方法叫 `createWaveform`（网上一堆文章写成 createWavePattern，
            // 那个名字在这套 SDK 里不存在，照抄就编译不过）；-1 = 不循环。
            setVibrationEffect(VibrationEffect.createWaveform(longArrayOf(0, 80, 120, 220), -1))
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
        return targetId
    }

    /** 删掉换代前的旧渠道（只在存在时删；删一次就够，重复调无害） */
    fun dropLegacy(manager: NotificationManager, legacyIds: List<String>) {
        legacyIds.forEach { runCatching { manager.deleteNotificationChannel(it) } }
    }

    private fun notificationAudioAttributes() = android.media.AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}
