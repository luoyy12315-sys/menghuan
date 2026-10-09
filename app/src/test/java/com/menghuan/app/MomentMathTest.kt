package com.menghuan.app

import com.menghuan.app.data.MomentMath
import com.menghuan.app.data.model.MomentEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态时间线的纯函数口径单测（v0.73.0）。
 *
 * 盯的是屏幕上一定会用到的三件事：**帖子倒序、回复正序、条数对得上**，
 * 以及点赞串的幂等（点两下 = 没点）—— 这三处错了，列表和详情页就会各说各话。
 */
class MomentMathTest {

    private fun post(id: Long, at: Long, parent: Long = 0L, text: String = "t", liked: String = "") =
        MomentEntry(id = id, personaId = 0L, parentId = parent, text = text, createdAt = at, likedBy = liked)

    @Test
    fun `帖子倒序而回复正序`() {
        val threads = MomentMath.threads(
            listOf(
                post(1, 1000),
                post(2, 3000),
                post(3, 2000),
                post(11, 1100, parent = 1),
                post(12, 1200, parent = 1),
            ),
        )
        assertEquals(listOf(2L, 3L, 1L), threads.map { it.post.id })
        val first = threads.last()
        assertEquals(listOf(11L, 12L), first.replies.map { it.id })
        assertEquals(2, first.replyCount)
    }

    @Test
    fun `指向不存在帖子的回复不进时间线`() {
        val threads = MomentMath.threads(listOf(post(9, 1000, parent = 999)))
        assertTrue(threads.isEmpty())
    }

    @Test
    fun `详情页按 id 取 thread`() {
        val all = listOf(post(1, 1000), post(11, 1100, parent = 1))
        assertEquals(1L, MomentMath.threadOf(all, 1L)?.post?.id)
        assertEquals(1, MomentMath.threadOf(all, 1L)?.replyCount)
        assertNull(MomentMath.threadOf(all, 77L))
    }

    @Test
    fun `点赞翻转是幂等的`() {
        val once = MomentMath.toggleLike("", "u")
        assertEquals("u", once)
        assertTrue(MomentMath.likedBy(once, "u"))
        assertEquals(1, MomentMath.likeCountOf(post(1, 1, liked = once)))
        // 再点一次 = 取消
        val twice = MomentMath.toggleLike(once, "u")
        assertEquals("", twice)
        assertFalse(MomentMath.likedBy(twice, "u"))
    }

    @Test
    fun `多个点赞者互不顶掉`() {
        val s = MomentMath.toggleLike(MomentMath.toggleLike("", "u"), "7")
        assertEquals(2, MomentMath.likeCountOf(post(1, 1, liked = s)))
        assertTrue(MomentMath.likedBy(s, "7"))
        // 只取消用户自己的那一个
        assertEquals("7", MomentMath.toggleLike(s, "u"))
    }

    @Test
    fun `相对时间按档位切换`() {
        val now = 1_700_000_000_000L
        assertEquals("刚刚", MomentMath.timeLabel(now - 30_000, now))
        assertEquals("5 分钟前", MomentMath.timeLabel(now - 5 * 60_000, now))
        assertEquals("3 小时前", MomentMath.timeLabel(now - 3 * 3_600_000, now))
        assertEquals("2 天前", MomentMath.timeLabel(now - 2 * 86_400_000, now))
        // 超过一周换成日期，固定用 Asia/Shanghai 免得单测跟着机器时区飘
        // （1.7e12 ms = 上海时间 2023-11-15 06:13，往前 10 天就是 11-05）
        val shanghai = java.time.ZoneId.of("Asia/Shanghai")
        assertEquals("11月5日", MomentMath.timeLabel(now - 10 * 86_400_000, now, shanghai))
    }

    @Test
    fun `未来的时间戳不显示成负数`() {
        assertEquals("刚刚", MomentMath.timeLabel(2_000L, 1_000L))
    }

    @Test
    fun `收口去空白并截到上限`() {
        assertEquals("你好", MomentMath.sanitize("  你好 \n"))
        assertEquals("", MomentMath.sanitize("   "))
        assertEquals(200, MomentMath.sanitize("啊".repeat(500), MomentMath.REPLY_MAX).length)
    }

    // ===== 图片串（v0.76.0）=====

    @Test
    fun `图片路径编解码原样往返`() {
        val paths = listOf("/a/1.jpg", "/b/2.png")
        assertEquals(paths, MomentMath.decodeImages(MomentMath.encodeImages(paths)))
        // 空串 = 没图（旧数据表上行默认就是这个值）
        assertTrue(MomentMath.decodeImages("").isEmpty())
    }

    @Test
    fun `图片串去重并截到九张`() {
        val encoded = MomentMath.encodeImages(
            (1..12).map { "/a/$it.jpg" } + "/a/1.jpg",
        )
        assertEquals(MomentMath.IMAGES_MAX, MomentMath.decodeImages(encoded).size)
        val first = MomentMath.decodeImages(encoded).first()
        assertEquals("/a/1.jpg", first)
    }

    /**
     * 逗号是**合法文件名字符**：当初没直接用逗号分隔，就是因为这类路径会把串拆错。
     * 这条红掉就说明分隔符被改回去了。
     */
    @Test
    fun `含逗号与空格的路径不被拆错`() {
        val odd = listOf("/dir/a,b.jpg", "/dir/ c .jpg")
        assertEquals(odd, MomentMath.decodeImages(MomentMath.encodeImages(odd)))
    }

    @Test
    fun `位置收口去空白压换行并截到上限`() {
        assertEquals("西湖边的星巴克", MomentMath.sanitizeLocation("  西湖边的星巴克 \n"))
        // 换行必须被压成空格：images 那一列用换行作分隔符，混进来会把串写坏
        assertEquals("a b", MomentMath.sanitizeLocation("a\nb"))
        assertEquals(MomentMath.LOCATION_MAX, MomentMath.sanitizeLocation("啊".repeat(80)).length)
        assertTrue(MomentMath.sanitizeLocation("   ").isEmpty())
    }
}
