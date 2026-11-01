package com.dreamdisplays.core.bridge

import com.dreamdisplays.api.media.search.MediaSearchPage as CompatSearchPage
import com.dreamdisplays.api.media.search.MediaSearchResult as CompatSearchResult
import com.dreamdisplays.api.media.search.SortOrder as CompatSortOrder
import com.dreamdisplays.api.media.source.MediaPlatform as CompatMediaPlatform
import com.dreamdisplays.api.media.source.MediaSource as CompatMediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia as CompatResolvedMedia
import com.dreamdisplays.api.media.stream.MediaStream as CompatStream
import com.dreamdisplays.api.media.stream.MediaStreamType as CompatStreamType
import com.dreamdisplays.api.media.stream.SubtitleTrack as CompatSubtitle
import com.dreamdisplayx.api.media.search.model.MediaSearchPage as XSearchPage
import com.dreamdisplayx.api.media.search.model.MediaSearchResult as XSearchResult
import com.dreamdisplayx.api.media.search.model.SortOrder as XSortOrder
import com.dreamdisplayx.api.media.source.model.MediaMetadata as XMetadata
import com.dreamdisplayx.api.media.source.model.MediaPlatform as XMediaPlatform
import com.dreamdisplayx.api.media.source.model.MediaSource as XMediaSource
import com.dreamdisplayx.api.media.source.model.ResolvedMedia as XResolvedMedia
import com.dreamdisplayx.api.media.source.model.SubtitleTrack as XSubtitle
import com.dreamdisplayx.api.media.stream.model.MediaStream as XStream
import com.dreamdisplayx.api.media.stream.model.MediaStreamType as XStreamType

/**
 * Conversions for the media types an addon can see through the compatible API.
 *
 * The fork and upstream media models carry the same fields; only the package names differ. The one deliberate
 * difference is stream backup URLs, which the fork pins to a stream and upstream keeps in its resolver: they are
 * dropped when going upstream and restored as an empty list coming back.
 *
 * @since 1.10.x
 */

internal fun XMediaPlatform.toCompat(): CompatMediaPlatform = when (this) {
    XMediaPlatform.YOUTUBE -> CompatMediaPlatform.YOUTUBE
    XMediaPlatform.TWITCH -> CompatMediaPlatform.TWITCH
    XMediaPlatform.VIMEO -> CompatMediaPlatform.VIMEO
    XMediaPlatform.KICK -> CompatMediaPlatform.KICK
    XMediaPlatform.BILIBILI -> CompatMediaPlatform.BILIBILI
    XMediaPlatform.DIRECT -> CompatMediaPlatform.DIRECT
    XMediaPlatform.OTHER -> CompatMediaPlatform.OTHER
}

internal fun CompatMediaPlatform.toFork(): XMediaPlatform = when (this) {
    CompatMediaPlatform.YOUTUBE -> XMediaPlatform.YOUTUBE
    CompatMediaPlatform.TWITCH -> XMediaPlatform.TWITCH
    CompatMediaPlatform.VIMEO -> XMediaPlatform.VIMEO
    CompatMediaPlatform.KICK -> XMediaPlatform.KICK
    CompatMediaPlatform.BILIBILI -> XMediaPlatform.BILIBILI
    CompatMediaPlatform.DIRECT -> XMediaPlatform.DIRECT
    CompatMediaPlatform.OTHER -> XMediaPlatform.OTHER
}

internal fun XSortOrder.toCompat(): CompatSortOrder = when (this) {
    XSortOrder.RELEVANCE -> CompatSortOrder.RELEVANCE
    XSortOrder.UPLOAD_DATE -> CompatSortOrder.UPLOAD_DATE
    XSortOrder.VIEW_COUNT -> CompatSortOrder.VIEW_COUNT
    XSortOrder.LIVE -> CompatSortOrder.LIVE
}

internal fun CompatSortOrder.toFork(): XSortOrder = when (this) {
    CompatSortOrder.RELEVANCE -> XSortOrder.RELEVANCE
    CompatSortOrder.UPLOAD_DATE -> XSortOrder.UPLOAD_DATE
    CompatSortOrder.VIEW_COUNT -> XSortOrder.VIEW_COUNT
    CompatSortOrder.LIVE -> XSortOrder.LIVE
}

/** Drops the fork-only Bilibili extras, which upstream addons have no field for. */
internal fun XSearchResult.toCompat(): CompatSearchResult = CompatSearchResult(
    id = id,
    title = title,
    uploader = uploader,
    durationSec = durationSec,
    viewCount = viewCount,
    likeCount = likeCount,
    publishedText = publishedText,
    publishedDaysAgo = publishedDaysAgo,
    watchUrlOverride = watchUrlOverride,
    thumbnailUrlOverride = thumbnailUrlOverride,
    isTwitch = isTwitch,
    isCustom = isCustom,
    platform = platform.toCompat(),
    channelAvatarUrl = channelAvatarUrl,
    isVerified = isVerified,
    isLive = isLive,
)

/** Rebuilds a fork result from the compatible shape; Bilibili extras stay `null`. */
internal fun CompatSearchResult.toFork(): XSearchResult = XSearchResult(
    id = id,
    title = title,
    uploader = uploader,
    durationSec = durationSec,
    viewCount = viewCount,
    likeCount = likeCount,
    publishedText = publishedText,
    publishedDaysAgo = publishedDaysAgo,
    watchUrlOverride = watchUrlOverride,
    thumbnailUrlOverride = thumbnailUrlOverride,
    isTwitch = isTwitch,
    isCustom = isCustom,
    platform = platform.toFork(),
    channelAvatarUrl = channelAvatarUrl,
    isVerified = isVerified,
    isLive = isLive,
)

internal fun XSearchPage.toCompat(): CompatSearchPage =
    CompatSearchPage(results.map { it.toCompat() }, continuationToken)

internal fun CompatSearchPage.toFork(): XSearchPage =
    XSearchPage(results.map { it.toFork() }, continuationToken)

internal fun XStreamType.toCompat(): CompatStreamType = when (this) {
    XStreamType.VIDEO -> CompatStreamType.VIDEO
    XStreamType.AUDIO -> CompatStreamType.AUDIO
    XStreamType.VIDEO_AUDIO -> CompatStreamType.VIDEO_AUDIO
}

internal fun CompatStreamType.toFork(): XStreamType = when (this) {
    CompatStreamType.VIDEO -> XStreamType.VIDEO
    CompatStreamType.AUDIO -> XStreamType.AUDIO
    CompatStreamType.VIDEO_AUDIO -> XStreamType.VIDEO_AUDIO
}

internal fun XStream.toCompat(): CompatStream = CompatStream(
    url = url,
    type = type.toCompat(),
    codec = codec,
    width = width,
    height = height,
    fps = fps,
    bitrate = bitrate,
    audioTrackName = audioTrackName,
    audioTrackLang = audioTrackLang,
    isDefault = isDefault,
    seekByDecoding = seekByDecoding,
)

internal fun CompatStream.toFork(): XStream = XStream(
    url = url,
    // An upstream stream has no backup URLs; the fork keeps an empty list for them.
    backupUrls = emptyList(),
    type = type.toFork(),
    codec = codec,
    width = width,
    height = height,
    fps = fps,
    bitrate = bitrate,
    audioTrackName = audioTrackName,
    audioTrackLang = audioTrackLang,
    isDefault = isDefault,
    seekByDecoding = seekByDecoding,
)

/**
 * Upstream subtitles carry `lang` + `displayName` + `isAutoGenerated`; the fork carries `language` + `name` +
 * `isDefault` / `isForced`. The language and label map across; a fork "forced" track has no upstream equivalent and
 * an upstream "auto-generated" track has no fork equivalent, so both flags are reset instead of being invented.
 */
internal fun XSubtitle.toCompat(): CompatSubtitle = CompatSubtitle(
    url = url,
    lang = language.orEmpty(),
    displayName = name,
    isAutoGenerated = false,
)

internal fun CompatSubtitle.toFork(): XSubtitle = XSubtitle(
    url = url,
    language = lang.ifBlank { null },
    name = displayName,
    isDefault = false,
    isForced = false,
)

internal fun XMetadata.toCompat(): com.dreamdisplays.api.media.source.MediaMetadata =
    com.dreamdisplays.api.media.source.MediaMetadata(
        title = title,
        uploader = uploader,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        viewCount = viewCount,
        likeCount = likeCount,
        uploadDate = uploadDate,
    )

internal fun com.dreamdisplays.api.media.source.MediaMetadata.toFork(): XMetadata = XMetadata(
    title = title,
    uploader = uploader,
    durationMs = durationMs,
    thumbnailUrl = thumbnailUrl,
    viewCount = viewCount,
    likeCount = likeCount,
    uploadDate = uploadDate,
)

internal fun XResolvedMedia.toCompat(): CompatResolvedMedia = CompatResolvedMedia(
    streams = streams.map { it.toCompat() },
    metadata = metadata.toCompat(),
    isLive = isLive,
    isSeekable = isSeekable,
    subtitles = subtitleTracks.map { it.toCompat() },
)

internal fun CompatResolvedMedia.toFork(): XResolvedMedia = XResolvedMedia(
    streams = streams.map { it.toFork() },
    metadata = metadata.toFork(),
    isLive = isLive,
    isSeekable = isSeekable,
    subtitleTracks = subtitles.map { it.toFork() },
)

internal fun XMediaSource.toCompat(): CompatMediaSource = when (this) {
    is XMediaSource.Remote -> CompatMediaSource.Remote(url)
    is XMediaSource.YouTube -> CompatMediaSource.YouTube(videoId)
    is XMediaSource.Twitch -> CompatMediaSource.Twitch(url, channel, videoId, clipSlug)
    is XMediaSource.Vimeo -> CompatMediaSource.Vimeo(url, videoId, hash)
    is XMediaSource.Kick -> CompatMediaSource.Kick(url, channel, videoUuid)
    is XMediaSource.Bilibili -> CompatMediaSource.Bilibili(url, bvid, avid, part, roomId, epId, seasonId)
    is XMediaSource.DirectStream -> CompatMediaSource.DirectStream(streamUrl, kind.toCompat())
    is XMediaSource.Ingest -> CompatMediaSource.Remote(url)
}

internal fun CompatMediaSource.toFork(): XMediaSource = when (this) {
    is CompatMediaSource.Remote -> XMediaSource.Remote(url)
    is CompatMediaSource.YouTube -> XMediaSource.YouTube(videoId)
    is CompatMediaSource.Twitch -> XMediaSource.Twitch(url, channel, videoId, clipSlug)
    is CompatMediaSource.Vimeo -> XMediaSource.Vimeo(url, videoId, hash)
    is CompatMediaSource.Kick -> XMediaSource.Kick(url, channel, videoUuid)
    is CompatMediaSource.Bilibili -> XMediaSource.Bilibili(url, bvid, avid, part, roomId, epId, seasonId)
    is CompatMediaSource.DirectStream -> XMediaSource.DirectStream(streamUrl, kind.toFork())
}

private fun com.dreamdisplayx.api.media.source.model.CustomMediaKind.toCompat():
    com.dreamdisplays.api.media.source.CustomMediaKind =
    com.dreamdisplays.api.media.source.CustomMediaKind.valueOf(name)

private fun com.dreamdisplays.api.media.source.CustomMediaKind.toFork():
    com.dreamdisplayx.api.media.source.model.CustomMediaKind =
    com.dreamdisplayx.api.media.source.model.CustomMediaKind.valueOf(name)