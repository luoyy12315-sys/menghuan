package com.menghuan.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 加密器（AGENTS §五.4）：密钥存在 AndroidKeyStore（硬件底座，不可导出），
 * DataStore 里只落密文 = Base64(IV(12B) + AES-256-GCM 密文)。
 * Keystore 不可用（部分 ROM/JVM 单测）或解密失败（锁屏凭据变更导致密钥失效）时
 * 返回 null，由 SettingsRepository 决定回退策略——这里绝不抛异常打断主流程。
 */
object ApiKeyCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "menghuan_api_key"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /**
     * 密钥句柄缓存：AndroidKeyStore 的 key 不可导出，句柄跨进程存活稳定。
     * 不缓存的话每次 encrypt/decrypt 都要 KeyStore.load + getEntry + doFinal
     * 三次 keystore2 binder 往返（真机实测一次解密可达十几毫秒）；缓存后只剩 doFinal 一次。
     */
    @Volatile
    private var cachedKey: SecretKey? = null

    /** 明文 → 密文；任何失败返回 null */
    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv ?: error("cipher IV missing")
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
    }.getOrNull()

    /** 密文 → 明文；密文格式非法/解密失败返回 null */
    fun decrypt(sealed: String): String? = runCatching {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) return@runCatching null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, raw, 0, IV_BYTES),
        )
        String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey.also { k -> cachedKey = k }
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey().also { cachedKey = it }
    }
}
