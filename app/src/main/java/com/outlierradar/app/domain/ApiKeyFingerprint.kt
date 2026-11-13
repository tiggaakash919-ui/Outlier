package com.outlierradar.app.domain

import java.security.MessageDigest

/**
 * Stable, non-reversible identifier for an API key.
 *
 * Only a truncated SHA-256 prefix is kept, so quota bookkeeping can be keyed by
 * key without ever persisting the secret again. Two different keys practically
 * never collide on 12 hex chars.
 */
object ApiKeyFingerprint {
  private const val LENGTH = 12

  fun of(apiKey: String): String {
    if (apiKey.isBlank()) return ""
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(apiKey.toByteArray(Charsets.UTF_8))
    val hex = buildString(digest.size * 2) {
      for (byte in digest) {
        val value = byte.toInt() and 0xFF
        append(HEX[value ushr 4])
        append(HEX[value and 0x0F])
      }
    }
    return hex.take(LENGTH)
  }

  private val HEX = "0123456789abcdef".toCharArray()
}
