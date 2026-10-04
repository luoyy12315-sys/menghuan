package com.menghuan.app

import com.menghuan.app.data.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 消息媒体类型推断（v0.43.0）：`mediaKind` 是 v13 新加的列，**存量消息全部是空串**。
 * 展示层必须靠 [MediaKind.fromLegacy] 把老数据正确还原，否则升级后历史消息会集体显示错。
 */
class MediaKindTest {

    @Test
    fun `老数据有图路径时推断为图片`() {
        // v12 及以前没有 mediaKind 列，只有 imagePath —— 升级后必须仍显示成图片
        assertEquals(MediaKind.IMAGE, MediaKind.fromLegacy("", "/sdcard/x.jpg"))
    }

    @Test
    fun `老数据没有图路径时推断为纯文本`() {
        assertEquals(MediaKind.TEXT, MediaKind.fromLegacy("", ""))
    }

    @Test
    fun `新数据显式标记优先于推断`() {
        assertEquals(MediaKind.AUDIO, MediaKind.fromLegacy("AUDIO", ""))
        assertEquals(MediaKind.FILE, MediaKind.fromLegacy("FILE", ""))
        // 语音消息没有 imagePath，靠显式标记才能被认出来
        assertEquals(MediaKind.AUDIO, MediaKind.fromLegacy("AUDIO", "/x.jpg"))
    }

    @Test
    fun `无法识别的取值回退为纯文本`() {
        // 防御：以后枚举改名/手写错值时不至于崩溃
        assertEquals(MediaKind.TEXT, MediaKind.fromLegacy("GIF", ""))
    }
}
