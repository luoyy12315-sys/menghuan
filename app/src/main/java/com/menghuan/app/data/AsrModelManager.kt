package com.menghuan.app.data

import android.content.Context
import android.net.Uri
import com.menghuan.app.llm.NetworkStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 端侧识别模型（SenseVoice int8）的两个文件。
 *
 * 字节数与 sha256 取自 ModelScope `pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue`
 * 仓库接口返回的官方值（2026-10-04 实测）——它就是 HuggingFace
 * `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` 的同内容镜像。
 */
enum class AsrModelFile(val fileName: String, val sizeBytes: Long, val sha256: String) {
    MODEL(
        "model.int8.onnx",
        239_233_841L,
        "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
    ),
    TOKENS(
        "tokens.txt",
        315_894L,
        "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
    ),
}

/**
 * 模型下载源。顺序即优先级。
 *
 * 为什么不是 GitHub Releases：2026-10-04 实测 github.com 直连 25s 超时 0 字节，
 * hf-mirror.com 直连 TLS 证书 SNI 校验失败，只有 modelscope.cn 秒通、
 * 回 `Accept-Ranges: bytes`（断点续传可用）并由接口给出官方 sha256。
 * hf-mirror 留作备源——本机证书报错不代表所有网络环境都报错。
 */
enum class AsrModelSource(val label: String) {
    MODELSCOPE("ModelScope"),
    HF_MIRROR("hf-mirror"),
    ;

    fun urlOf(file: AsrModelFile): String = when (this) {
        MODELSCOPE ->
            "https://www.modelscope.cn/api/v1/models/$MODELSCOPE_REPO/repo" +
                "?Revision=master&FilePath=${file.fileName}"

        HF_MIRROR -> "https://hf-mirror.com/$HF_REPO/resolve/main/${file.fileName}"
    }

    private companion object {
        const val MODELSCOPE_REPO = "pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue"
        const val HF_REPO = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
    }
}

/** 手动导入的结果：校验通过 / 大小对得上但校验值不符（仍装，但要告诉用户） / 装不上 */
sealed interface AsrImportResult {
    data object Verified : AsrImportResult
    data class AcceptedWithWarning(val reason: String) : AsrImportResult
    data class Failed(val reason: String) : AsrImportResult
}

/**
 * 纯逻辑（不碰 Android、不碰网络），供 JVM 单测直接覆盖。
 */
object AsrModelRules {

    /** 续传决策：本地 .part 比目标还大 = 脏数据，必须从头来 */
    sealed interface Resume {
        data object FromScratch : Resume
        data class From(val offset: Long) : Resume
        data object AlreadyComplete : Resume
    }

    fun resumeDecision(partLength: Long, expected: Long): Resume = when {
        partLength <= 0L -> Resume.FromScratch
        partLength >= expected -> Resume.AlreadyComplete
        else -> Resume.From(partLength)
    }

    /** Range 头：offset 为 0 时不能发（部分 CDN 对 `bytes=0-` 回 200 而非 206，逻辑虽同但要少一个分支） */
    fun rangeHeader(offset: Long): String? = if (offset > 0L) "bytes=$offset-" else null

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { String.format("%02x", it) }
    }

    /** 已下载字节 → 0f..1f（total 为 0 时给 0，避免除零） */
    fun fraction(done: Long, total: Long): Float =
        if (total <= 0L) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    fun humanSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

/**
 * SenseVoice 离线识别模型的下载 / 校验 / 导入 / 清理（v0.50.0）。
 *
 * ## 为什么模型不随包
 * int8 权重实测 228MB（fp32 894MB），塞进 APK 会让安装包过百兆、上架与更新都难受。
 * 用户已拍板「零内置 + 装后下载」：文字聊天不依赖模型，只有语音输入与通话需要。
 *
 * ## 就绪判定为什么不每次重算 sha256
 * 228MB 全量哈希在中端机上要好几秒，而 `isReady` 会在每次点语音键、每次进通话页时被调用。
 * 所以哈希只在**下载完成**与**手动导入**时各算一次，通过后写一个 `.verified` 标记；
 * `isReady` 只看「两个文件都在 + 字节数与官方一致 + 标记存在」。字节数全等已经是很强的过滤。
 *
 * ## 断点续传
 * 下载写 `<name>.part`，恢复时按 `.part` 现有长度发 `Range: bytes=<len>-` 追加。
 * 暂停 = 取消协程（`CancellationException` 原样上抛，不吞成失败），继续 = 再调一次 download。
 */
object AsrModelManager {

    private const val DIR_NAME = "asr_sensevoice"
    private const val VERIFIED_MARK = ".verified"

    private val lock = Any()

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun fileOf(context: Context, which: AsrModelFile): File = File(dir(context), which.fileName)

    private fun markFile(context: Context) = File(dir(context), VERIFIED_MARK)

    private fun partFileOf(context: Context, which: AsrModelFile) =
        File(dir(context), "${which.fileName}.part")

    /** 两个文件齐全、字节数与官方一致、且已通过一次完整校验 */
    fun isReady(context: Context): Boolean = runCatching {
        markFile(context).exists() &&
            AsrModelFile.entries.all { f -> fileOf(context, f).let { it.isFile && it.length() == f.sizeBytes } }
    }.getOrDefault(false)

    /** 缺哪个文件（给 UI 显示"还需下载 228.2 MB"这类文案） */
    fun missingFiles(context: Context): List<AsrModelFile> =
        AsrModelFile.entries.filter { f ->
            val it = fileOf(context, f)
            !it.isFile || it.length() != f.sizeBytes
        }

    /** 还差多少字节（扣掉已续传的 .part），给 UI 显示"还需下载 213.4 MB" */
    fun pendingBytes(context: Context): Long = missingFiles(context).sumOf { f ->
        val done = partFileOf(context, f).takeIf { it.isFile }?.length()?.coerceAtMost(f.sizeBytes) ?: 0L
        f.sizeBytes - done
    }

    /**
     * 下载缺失的文件。已成功校验过的文件会跳过，所以"下了一半退出再进来"不会重下 228MB。
     *
     * @param onProgress (当前文件, 已完成字节, 总字节)
     * @throws CancellationException 暂停时由调用方取消协程触发，**不会被包进返回值**
     */
    suspend fun download(
        context: Context,
        source: AsrModelSource,
        onProgress: (AsrModelFile, Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            dir(context).mkdirs()
        }
        val targets = missingFiles(context)
        if (targets.isEmpty()) {
            finalizeIfComplete(context)
            return@withContext Result.success(Unit)
        }

        val http = NetworkStack.baseBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS) // 228MB 单文件，整体超时由用户暂停控制
            .build()

        for (which in targets) {
            val outcome = runCatching { fetchOne(context, http, source, which, onProgress) }
            if (outcome.isFailure) {
                val cause = outcome.exceptionOrNull()
                if (cause is CancellationException) throw cause
                return@withContext Result.failure(
                    IllegalStateException(
                        "${which.fileName} 下载失败：${describe(cause)}",
                        cause,
                    ),
                )
            }
        }
        finalizeIfComplete(context)
        Result.success(Unit)
    }

    private suspend fun fetchOne(
        context: Context,
        http: okhttp3.OkHttpClient,
        source: AsrModelSource,
        which: AsrModelFile,
        onProgress: (AsrModelFile, Long, Long) -> Unit,
    ) {
        val target = fileOf(context, which)
        val part = partFileOf(context, which)

        when (val decision = AsrModelRules.resumeDecision(part.takeIf { it.isFile }?.length() ?: 0L, which.sizeBytes)) {
            AsrModelRules.Resume.AlreadyComplete -> {
                requireVerified(context, which, part)
                if (!part.renameTo(target)) {
                    throw IllegalStateException("写入 ${which.fileName} 失败（重命名 .part 未成功）")
                }
                return
            }

            AsrModelRules.Resume.FromScratch -> runCatching { part.delete() }

            is AsrModelRules.Resume.From -> {
                val free = context.filesDir.usableSpace
                if (free < which.sizeBytes - decision.offset) {
                    throw IllegalStateException(
                        "存储空间不足：还需 ${AsrModelRules.humanSize(which.sizeBytes - decision.offset)}，" +
                            "可用 ${AsrModelRules.humanSize(free)}",
                    )
                }
            }
        }

        val offset = part.takeIf { it.isFile }?.length() ?: 0L
        val request = Request.Builder()
            .url(source.urlOf(which))
            .apply { AsrModelRules.rangeHeader(offset)?.let { addHeader("Range", it) } }
            .build()

        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} ${source.label}")
            }
            val body = resp.body ?: throw IllegalStateException("响应体为空")
            // 服务端不支持 Range 时会回 200 + 完整文件，此时必须从 0 重写，否则文件会拼坏
            val append = offset > 0L && resp.code == 206
            if (!append && offset > 0L) runCatching { part.delete() }

            var written = if (append) offset else 0L
            java.io.FileOutputStream(part, append).use { out ->
                val input: InputStream = body.byteStream()
                val buf = ByteArray(64 * 1024) // 228MB 单文件，8KB 缓冲会让回调次数翻八倍
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    written += n
                    onProgress(which, written, which.sizeBytes)
                }
                out.flush()
            }
            if (written != which.sizeBytes) {
                throw IllegalStateException(
                    "下载不完整：收到 ${AsrModelRules.humanSize(written)}，" +
                        "应为 ${AsrModelRules.humanSize(which.sizeBytes)}（可继续下载续传）",
                )
            }
        }
        requireVerified(context, which, part)
        if (!part.renameTo(target)) {
            throw IllegalStateException("写入 ${which.fileName} 失败（重命名 .part 未成功）")
        }
    }

    /** 校验单个文件的 sha256，不符就删掉 .part（脏数据留着只会让下次续传拼出坏文件） */
    private fun requireVerified(context: Context, which: AsrModelFile, downloaded: File) {
        val actual = downloaded.inputStream().use { AsrModelRules.sha256Hex(it) }
        if (!actual.equals(which.sha256, ignoreCase = true)) {
            runCatching { downloaded.delete() }
            throw IllegalStateException(
                "${which.fileName} 校验失败（sha256 与官方不符），已删除，请重试或换一个下载源",
            )
        }
    }

    private fun finalizeIfComplete(context: Context) {
        if (missingFiles(context).isEmpty()) {
            runCatching { markFile(context).writeText(AsrModelFile.MODEL.sha256) }
        }
    }

    /** SAF 手动导入：大小必须全等（挡掉选错文件），sha256 不符时仍装但明确告警 */
    suspend fun importFrom(
        context: Context,
        uri: Uri,
        which: AsrModelFile,
    ): AsrImportResult = withContext(Dispatchers.IO) {
        synchronized(lock) { dir(context).mkdirs() }
        val target = fileOf(context, which)
        val staging = partFileOf(context, which)
        runCatching { staging.delete() }

        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(staging).use { out -> input.copyTo(out) }
                staging.length()
            } ?: -1L
        }.getOrElse {
            return@withContext AsrImportResult.Failed("读取文件失败：${describe(it)}")
        }

        if (copied <= 0L) return@withContext AsrImportResult.Failed("文件为空")
        if (copied != which.sizeBytes) {
            runCatching { staging.delete() }
            return@withContext AsrImportResult.Failed(
                "文件大小不符：${AsrModelRules.humanSize(copied)}，" +
                    "官方 ${which.fileName} 应为 ${AsrModelRules.humanSize(which.sizeBytes)}",
            )
        }

        val sha = runCatching { staging.inputStream().use { AsrModelRules.sha256Hex(it) } }.getOrNull()
        if (!sha.equals(which.sha256, ignoreCase = true)) {
            // 大小逐字节相同却哈希不同：几乎只可能是另一个构建。用户是自己挑的文件，
            // 装上用，但把话说清楚，不假装校验通过。
            if (!runCatching { staging.renameTo(target) }.getOrDefault(false)) {
                return@withContext AsrImportResult.Failed("写入 ${which.fileName} 失败")
            }
            finalizeIfComplete(context)
            return@withContext AsrImportResult.AcceptedWithWarning(
                "已导入，但校验值与官方模型不一致（可能是另一构建），识别异常时请重新下载",
            )
        }

        if (!runCatching { staging.renameTo(target) }.getOrDefault(false)) {
            return@withContext AsrImportResult.Failed("写入 ${which.fileName} 失败")
        }
        finalizeIfComplete(context)
        AsrImportResult.Verified
    }

    /** 释放空间：模型与标记一起删，删完 `isReady` 立刻变 false */
    fun delete(context: Context) {
        runCatching {
            markFile(context).delete()
            AsrModelFile.entries.forEach { f ->
                fileOf(context, f).delete()
                partFileOf(context, f).delete()
            }
        }
    }

    private fun describe(t: Throwable?): String {
        val msg = t?.message?.takeIf { it.isNotBlank() }
        return if (msg != null) "${t!!.javaClass.simpleName}: $msg"
        else (t?.javaClass?.simpleName ?: "未知原因")
    }
}

/** 下载进度（面板据此渲染；Idle = 没在下） */
sealed interface AsrDownloadPhase {
    data object Idle : AsrDownloadPhase
    data class Running(val file: AsrModelFile, val doneBytes: Long, val totalBytes: Long) : AsrDownloadPhase
    data class Failed(val reason: String) : AsrDownloadPhase
}

/**
 * 进程级下载会话。
 *
 * 为什么不用面板自己的 `rememberCoroutineScope`：228MB 在手机上要下几分钟，
 * 用户中途关掉面板是常态，绑在 Compose 作用域上会让下载被"关面板"这个动作掐断。
 * 挂到 `MenghuanApp.applicationScope` 上，面板重开还能接着看进度；
 * 暂停 = 取消协程，已落盘的 `.part` 由下次 start 自动续传。
 */
object AsrDownloadSession {

    private val _phase = MutableStateFlow<AsrDownloadPhase>(AsrDownloadPhase.Idle)
    val phase: StateFlow<AsrDownloadPhase> = _phase

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start(context: Context, scope: CoroutineScope, source: AsrModelSource) {
        if (isRunning) return
        _phase.value = AsrDownloadPhase.Idle
        job = scope.launch {
            var lastPercent = -1
            try {
                AsrModelManager.download(context, source) { file, done, total ->
                    // 64KB 一个回调，228MB 会有三千多次；只在整百分比变化时推给 UI
                    val percent = AsrModelRules.fraction(done, total).times(100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _phase.value = AsrDownloadPhase.Running(file, done, total)
                    }
                }.onFailure { t ->
                    _phase.value = AsrDownloadPhase.Failed(t.message ?: "下载失败")
                }
            } catch (ce: CancellationException) {
                _phase.value = AsrDownloadPhase.Idle
                throw ce
            } finally {
                job = null
                if (_phase.value is AsrDownloadPhase.Running) _phase.value = AsrDownloadPhase.Idle
            }
        }
    }

    fun pause() {
        job?.cancel()
        job = null
        _phase.value = AsrDownloadPhase.Idle
    }

    /** 失败提示看过一次就清掉，别一直挂在面板上 */
    fun clearFailure() {
        if (_phase.value is AsrDownloadPhase.Failed) _phase.value = AsrDownloadPhase.Idle
    }
}
