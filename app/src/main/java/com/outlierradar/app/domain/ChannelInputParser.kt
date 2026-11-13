package com.outlierradar.app.domain

/**
 * Parses the many shapes a user can paste for CHANNEL_SCAN:
 * `@handle`, `https://youtube.com/@handle`, `youtube.com/channel/UC…`,
 * `youtube.com/c/name`, `youtube.com/user/name`, or a bare `UC…` id.
 */
object ChannelInputParser {

  enum class Kind { ID, HANDLE, USERNAME }

  data class ChannelTarget(val kind: Kind, val value: String, val display: String)

  private val CHANNEL_ID_REGEX = Regex("^UC[\\w-]{10,}$")
  private val HANDLE_REGEX = Regex("^[\\w.\\-]{2,}$")

  fun parse(raw: String): ChannelTarget? {
    var text = raw.trim()
    if (text.isEmpty()) return null
    text = text.substringBefore('?').substringBefore('#').trimEnd('/')

    val lower = text.lowercase()
    when {
      lower.contains("youtube.com/channel/") -> {
        val id = text.substringAfter("channel/", "").substringBefore('/').trim()
        return if (CHANNEL_ID_REGEX.matches(id)) {
          ChannelTarget(Kind.ID, id, "UC…${id.takeLast(6)}")
        } else {
          null
        }
      }
      lower.contains("/c/") -> {
        val name = text.substringAfter("/c/", "").substringBefore('/').trim()
        return name.takeIf { it.isNotEmpty() }?.let {
          ChannelTarget(Kind.USERNAME, it, "@$it")
        }
      }
      lower.contains("/user/") -> {
        val name = text.substringAfter("/user/", "").substringBefore('/').trim()
        return name.takeIf { it.isNotEmpty() }?.let {
          ChannelTarget(Kind.USERNAME, it, "@$it")
        }
      }
      lower.contains("/@") -> {
        val handle = text.substringAfter("/@", "").substringBefore('/').trim()
        return handle.toHandleTarget()
      }
      text.startsWith("@") -> return text.drop(1).trim().toHandleTarget()
      CHANNEL_ID_REGEX.matches(text) -> {
        return ChannelTarget(Kind.ID, text, "UC…${text.takeLast(6)}")
      }
      lower.startsWith("youtube.com/") || lower.startsWith("www.youtube.com/") -> {
        val name = text.substringAfter("youtube.com/", "").substringBefore('/').trim()
        return name.toHandleTarget()
      }
      else -> return text.toHandleTarget()
    }
  }

  private fun String.toHandleTarget(): ChannelTarget? {
    val handle = trim().removePrefix("@").trim()
    if (handle.isEmpty()) return null
    if (CHANNEL_ID_REGEX.matches(handle)) {
      return ChannelTarget(Kind.ID, handle, "UC…${handle.takeLast(6)}")
    }
    if (!HANDLE_REGEX.matches(handle)) return null
    return ChannelTarget(Kind.HANDLE, handle, "@$handle")
  }
}
