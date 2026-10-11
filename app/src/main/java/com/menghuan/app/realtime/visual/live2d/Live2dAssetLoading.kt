package com.menghuan.app.realtime.visual.live2d

import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import com.live2d.sdk.cubism.framework.CubismModelSettingJson
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid
import java.io.File

/**
 * 模型包的**资产加载**：读包内文件、建渲染器、把贴图传上 GPU（v0.82.0 P2-3 从
 * `Live2dCharacter` 拆出来）。
 *
 * 这一段全是"要 EGL 上下文"的活（纹理与渲染器），所以函数都收在 [CubismRendererAndroid]
 * 这一层：它拿 `setting` 与包根目录，把 `Textures` 段里列到的每一张图传上去。
 * 传不上去的贴图**只跳过这一张**——缺一张图不该让整张模型降级（方案文档 §二十四）。
 */
internal object Live2dAssetLoading {

    /**
     * `CubismRendererAndroid.create` 声明返回基类 `CubismRenderer`（框架自己的写法），
     * 但 Android 实现只有 `CubismRendererAndroid` 一个，取不到就是初始化失败 ——
     * 抛出去由调用方降级成头像，不许在这里返回 null 让后面 NPE。
     */
    fun createRenderer(width: Int, height: Int): CubismRendererAndroid =
        CubismRendererAndroid.create(width, height) as? CubismRendererAndroid
            ?: error("Live2D 渲染器创建失败（这台机器的 OpenGL 环境不支持）")

    /**
     * 把 `Textures` 段里列到的每一张图传上 GPU 并绑到渲染器；返回传成功的那几张的纹理 id
     * （卸载时由角色自己记着去删）。
     */
    fun setupTextures(
        setting: CubismModelSettingJson,
        baseDir: File,
        modelJsonRelPath: String,
        renderer: CubismRendererAndroid,
    ): List<Int> {
        val ids = ArrayList<Int>()
        for (i in 0 until setting.textureCount) {
            val rel = setting.getTextureFileName(i) ?: continue
            val bytes = runCatching { readRef(baseDir, modelJsonRelPath, rel) }.getOrNull() ?: continue
            val id = uploadTexture(bytes) ?: continue
            renderer.bindTexture(i, id)
            ids.add(id)
        }
        renderer.isPremultipliedAlpha(true)
        return ids
    }

    /**
     * model3.json 里的引用都是相对它自己所在目录的。
     *
     * 少数导出包会把路径写成相对包根，所以再兜一次；两次都找不到才算缺文件
     * （导入阶段的校验用的是同一个口径，能对得上）。
     */
    fun readRef(baseDir: File, modelJsonRelPath: String, rel: String): ByteArray {
        val jsonDir = File(baseDir, modelJsonRelPath).parentFile ?: baseDir
        val direct = File(jsonDir, rel)
        if (direct.isFile) return direct.readBytes()
        val atRoot = File(baseDir, rel)
        if (atRoot.isFile) return atRoot.readBytes()
        error("模型包缺少文件 $rel")
    }

    private fun uploadTexture(bytes: ByteArray): Int? = runCatching {
        val opts = BitmapFactory.Options().apply { inPremultiplied = true }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        // v0.97.0：**上传途中任何一步抛异常都不能把解码出来的位图漏掉**。
        // 以前 `bitmap.recycle()` 写在最后一行 —— texImage2D / glGenerateMipmap 一抛异常
        // 就跳过回收，而贴图常见 2048²/4096²，漏一张就是几十 MB 的 native 内存；
        // 导入的模型又没有纹理尺寸上限，多漏几张就是 OOM。
        try {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR,
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            ids[0]
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()
}
