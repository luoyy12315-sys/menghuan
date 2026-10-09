package com.menghuan.app.data

/**
 * 语义向量的落库编解码（v0.82.0 P1-2，纯函数、JVM 单测口径）。
 *
 * float32 小端字节序：与各家 embeddings 端点回的数字数组一一对应，
 * 维度由服务端决定（768 / 1024 / 1536 / 3072 都有），这里不假设、不校验——
 * 维度不一致的向量在 [MemoryScope.cosine] 里自然算成 0，不需要在这里拦。
 */
object EmbeddingVector {

    fun encode(vector: FloatArray): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(vector.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buf.putFloat(it) }
        return buf.array()
    }

    /** 字节数不是 4 的倍数 = 数据坏了，返回空向量（余弦自然算 0，不拿半个 float 凑数） */
    fun decode(bytes: ByteArray): FloatArray {
        if (bytes.isEmpty() || bytes.size % 4 != 0) return FloatArray(0)
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.getFloat() }
    }
}
