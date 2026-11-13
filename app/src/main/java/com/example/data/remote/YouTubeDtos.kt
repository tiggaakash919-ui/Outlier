package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SearchResponse(
  val nextPageToken: String? = null,
  val items: List<SearchItem>? = null
)

@JsonClass(generateAdapter = true)
data class SearchItem(
  val id: SearchId? = null
)

@JsonClass(generateAdapter = true)
data class SearchId(
  val videoId: String? = null
)

@JsonClass(generateAdapter = true)
data class VideoListResponse(
  val items: List<VideoItem>? = null
)

@JsonClass(generateAdapter = true)
data class VideoItem(
  val id: String,
  val snippet: VideoSnippet? = null,
  val statistics: VideoStatistics? = null,
  val contentDetails: VideoContentDetails? = null
)

@JsonClass(generateAdapter = true)
data class VideoSnippet(
  val title: String? = null,
  val description: String? = null,
  val channelId: String? = null,
  val channelTitle: String? = null,
  val publishedAt: String? = null,
  val tags: List<String>? = null,
  val thumbnails: VideoThumbnails? = null
)

@JsonClass(generateAdapter = true)
data class VideoThumbnails(
  val default: ThumbnailInfo? = null,
  val medium: ThumbnailInfo? = null,
  val high: ThumbnailInfo? = null,
  val standard: ThumbnailInfo? = null,
  val maxres: ThumbnailInfo? = null
) {
  fun bestUrl(): String {
    return maxres?.url
      ?: standard?.url
      ?: high?.url
      ?: medium?.url
      ?: default?.url
      ?: ""
  }
}

@JsonClass(generateAdapter = true)
data class ThumbnailInfo(
  val url: String? = null
)

@JsonClass(generateAdapter = true)
data class VideoStatistics(
  val viewCount: String? = null,
  val likeCount: String? = null,
  val commentCount: String? = null
)

@JsonClass(generateAdapter = true)
data class VideoContentDetails(
  val duration: String? = null
)

@JsonClass(generateAdapter = true)
data class ChannelListResponse(
  val items: List<ChannelItem>? = null
)

@JsonClass(generateAdapter = true)
data class ChannelItem(
  val id: String,
  val snippet: ChannelSnippet? = null,
  val statistics: ChannelStatistics? = null,
  val contentDetails: ChannelContentDetails? = null
)

@JsonClass(generateAdapter = true)
data class ChannelSnippet(
  val title: String? = null
)

@JsonClass(generateAdapter = true)
data class ChannelStatistics(
  val subscriberCount: String? = null,
  val hiddenSubscriberCount: Boolean? = null,
  val videoCount: String? = null
)

@JsonClass(generateAdapter = true)
data class ChannelContentDetails(
  val relatedPlaylists: RelatedPlaylists? = null
)

@JsonClass(generateAdapter = true)
data class RelatedPlaylists(
  val uploads: String? = null
)

@JsonClass(generateAdapter = true)
data class PlaylistItemListResponse(
  val nextPageToken: String? = null,
  val items: List<PlaylistItem>? = null
)

@JsonClass(generateAdapter = true)
data class PlaylistItem(
  val snippet: PlaylistItemSnippet? = null,
  val contentDetails: PlaylistItemContentDetails? = null
)

@JsonClass(generateAdapter = true)
data class PlaylistItemSnippet(
  val title: String? = null,
  val publishedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class PlaylistItemContentDetails(
  val videoId: String? = null,
  val videoPublishedAt: String? = null
) {
  /** The playlist item's own publish date is the most reliable one. */
  fun bestPublishedAt(snippetPublishedAt: String?): String? {
    return videoPublishedAt?.takeIf { it.isNotBlank() }
      ?: snippetPublishedAt?.takeIf { it.isNotBlank() }
  }
}

@JsonClass(generateAdapter = true)
data class GoogleErrorEnvelope(
  val error: GoogleErrorDetails? = null
)

@JsonClass(generateAdapter = true)
data class GoogleErrorDetails(
  val code: Int? = null,
  val message: String? = null,
  val errors: List<GoogleErrorReason>? = null
)

@JsonClass(generateAdapter = true)
data class GoogleErrorReason(
  val message: String? = null,
  val domain: String? = null,
  val reason: String? = null
)
