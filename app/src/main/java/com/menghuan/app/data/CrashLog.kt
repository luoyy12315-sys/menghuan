package com.menghuan.app.data

import android.content.Context
import android.os.Build
import com.menghuan.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃现场落盘（v0.53.1）。
 *
 * **为什么要自己接**：通话播放那条协程（`PipelineVoiceCallEngine.ttsPlayer`）跑在
 * `applicationScope` 上，里面任何没被 catch 的异常都会直接冒到默认处理器 → 进程死掉，
 * 而用户侧只看到"闪退"，我这边一行堆栈都拿不到（真机没有 logcat）。
 * 现在：崩溃前先把堆栈写进 `filesDir/crash.log`，「我的 → 设置 → 关于」里能直接打开看，
 * 用户截一张图就能定位；写完仍交回给原处理器，系统该重启重启，不改变原有行为。
 */
object CrashLog {

    private const val FILE_NAME = "crash.log"
    private const val MAX_CHARS = 6_000

    /** 只装一次：重复安装会把上一个"原处理器"接成环 */
    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun hasCrash(context: Context): Boolean = file(context).length() > 0L

    /** 最近一次崩溃内容（供「关于」页展示）；没有则 null */
    fun latest(context: Context): String? = runCatching {
        val f = file(context)
        if (!f.isFile || f.length() == 0L) null else f.readText().take(MAX_CHARS)
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter().use { sw ->
            error.printStackTrace(PrintWriter(sw))
            sw.toString()
        }
        val when1 = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val text = buildString {
            append(when1)
            append("  梦欢 v").append(BuildConfig.VERSION_NAME)
            append("  API ").append(Build.VERSION.SDK_INT)
            append("  ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("线程：").append(thread.name).append('\n')
            append(stack.take(MAX_CHARS))
        }
        // 直接写文件，不新开协程：进程马上就没了，协程调度器可能已经不可用
        file(context).writeText(text)
    }
}
