package com.example.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyVault {
  private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
    load(null)
  }

  init {
    ensureKeyExists()
  }

  private fun ensureKeyExists() {
    if (!keyStore.containsAlias(KEY_ALIAS)) {
      val keyGenerator = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        ANDROID_KEYSTORE
      )
      val spec = KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()

      keyGenerator.init(spec)
      keyGenerator.generateKey()
    }
  }

  private fun getSecretKey(): SecretKey {
    return keyStore.getKey(KEY_ALIAS, null) as SecretKey
  }

  /**
   * Encrypts plain text using AES/GCM/NoPadding.
   * Returns base64 encoded string containing 12-byte IV + ciphertext + tag.
   */
  fun encrypt(plainText: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
    val iv = cipher.iv // 12 bytes for GCM
    val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

    val combined = ByteArray(iv.size + encrypted.size)
    System.arraycopy(iv, 0, combined, 0, iv.size)
    System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

    return Base64.encodeToString(combined, Base64.NO_WRAP)
  }

  /**
   * Decrypts ciphertext produced by encrypt().
   */
  fun decrypt(cipherTextBase64: String): String {
    val combined = Base64.decode(cipherTextBase64, Base64.NO_WRAP)
    if (combined.size < GCM_IV_LENGTH) {
      throw IllegalArgumentException("Invalid encrypted payload size")
    }

    val iv = ByteArray(GCM_IV_LENGTH)
    val cipherText = ByteArray(combined.size - GCM_IV_LENGTH)
    System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
    System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.size)

    val cipher = Cipher.getInstance(TRANSFORMATION)
    val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
    cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

    val decryptedBytes = cipher.doFinal(cipherText)
    return String(decryptedBytes, Charsets.UTF_8)
  }

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "OutlierRadarKey_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128
  }
}
