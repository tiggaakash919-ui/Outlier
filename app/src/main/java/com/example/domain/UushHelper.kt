package com.example.domain

object UushHelper {
  /**
   * Generates unofficial Shorts playlist ID for a given channel ID.
   * e.g., UC_x5XG1OV2P6uZZ5FSM9Ttw -> UUSH_x5XG1OV2P6uZZ5FSM9Ttw
   */
  fun getShortsPlaylistId(channelId: String): String {
    return if (channelId.startsWith("UC") && channelId.length > 2) {
      "UUSH" + channelId.drop(2)
    } else {
      "UUSH$channelId"
    }
  }
}
