package com.menghuan.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.AiCall
import com.menghuan.app.data.AiCallReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 「AI 打电话」的闹钟落点（v0.94 起**只剩一路**：用户在聊天里点名要的那一通）。
 *
 * 口头约定那一路（`once`）带 personaId：用户让谁打就谁打，不看规则、不看静默、不算次数
 * （用户踩过："我叫他打，他没打过来"——所以这条路什么都不拦）。
 *
 * ⚠️ v0.94：原来那条"每天检查一次、够 X 天没聊就主动打"的智能分支**整条下线** ——
 * 主动电话并入主动关心的随机时刻（ProactiveChat.maybeGreet → AiCall.proactiveRing），
 * 这里不再有那套判定。
 *
 * ⚠️ 这里只做分流，不调 LLM：开场白生成在 [AiCall.ring] 里（网络请求），
 * 所以整段走 `goAsync()` + IO 线程。
 */
class AiCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val personaId = intent.getLongExtra(AlarmScheduler.EXTRA_AI_CALL_PERSONA, 0L)
        val once = intent.getBooleanExtra(AlarmScheduler.EXTRA_AI_CALL_ONCE, false)
        if (personaId <= 0L || !once) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? MenghuanApp ?: return@launch
                AiCall.ring(context, app.db, app.settingsRepository, personaId, AiCallReason.REQUESTED)
            } catch (t: Throwable) {
                // 静默：一次没打出去不该影响任何东西
            } finally {
                pending.finish()
            }
        }
    }
}
