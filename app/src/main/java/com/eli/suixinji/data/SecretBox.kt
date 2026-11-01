package com.eli.suixinji.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.eli.suixinji.util.AppLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本机敏感字段加密（v1.1）：API Key 不落明文 DataStore。
 *
 * 密钥由 Android Keystore 生成并持有（AES-256/GCM，别名不出设备），
 * DataStore 只存 `enc:v1:<base64(iv+ciphertext)>`。卸载/换机后密钥不可导出，
 * 需要用户重新填写——这是预期行为。
 */
object SecretBox {

  private const val KEYSTORE = "AndroidKeyStore"
  private const val ALIAS = "suixinji_secret_v1"
  private const val TRANSFORM = "AES/GCM/NoPadding"
  private const val PREFIX = "enc:v1:"
  private const val IV_BYTES = 12
  private const val TAG_BITS = 128

  fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

  /** 加密失败时抛出，由调用方提示用户，不静默回退明文。 */
  fun encrypt(plain: String): String {
    val cipher = Cipher.getInstance(TRANSFORM)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    val iv = cipher.iv
    require(iv.size == IV_BYTES) { "unexpected GCM IV size=${iv.size}" }
    return PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
  }

  /** 解密失败返回 null（例如备份恢复后 Keystore 密钥丢失），UI 视为未配置。 */
  fun decrypt(stored: String?): String? {
    if (stored.isNullOrBlank()) return null
    if (!stored.startsWith(PREFIX)) return stored // 兼容旧版明文，由迁移逻辑改写
    return runCatching {
      val all = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
      require(all.size > IV_BYTES)
      val iv = all.copyOfRange(0, IV_BYTES)
      val ct = all.copyOfRange(IV_BYTES, all.size)
      val cipher = Cipher.getInstance(TRANSFORM)
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
      String(cipher.doFinal(ct), Charsets.UTF_8)
    }.onFailure {
      AppLog.w("SecretBox：敏感字段解密失败（Keystore 密钥可能已丢失），按未配置处理")
    }.getOrNull()
  }

  private fun key(): SecretKey {
    val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(
        ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true)
        .build(),
    )
    return generator.generateKey()
  }
}
