package com.dreamdisplayx.media.source.ingest

import com.dreamdisplayx.api.media.source.model.MediaMetadata
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.api.media.source.model.ResolvedMedia
import com.dreamdisplayx.api.media.source.service.MediaResolverService
import com.dreamdisplayx.api.media.stream.model.MediaStream
import com.dreamdisplayx.api.media.stream.model.MediaStreamType

/**
 * Resolves a live ingest endpoint (`rtmp://`, `rtmps://`, `srt://`) that a client pushes to —
 * screen sharing / casting. Nothing is probed: the endpoint is push-only, so the stream is reported
 * live and never seekable, and the player opens it like any other live source.
 */
object IngestResolver : MediaResolverService {
    override val priority: Int = 30

    override fun canResolve(source: MediaSource): Boolean = source is MediaSource.Ingest

    /** There is nothing to warm up — probing an ingest endpoint would block until a push starts. */
    override fun prefetch(source: MediaSource): Boolean = false

    override fun resolve(source: MediaSource): ResolvedMedia {
        val ingest = source as? MediaSource.Ingest
            ?: throw UnsupportedOperationException("$source is not an ingest source.")
        return ResolvedMedia(
            streams = listOf(
                MediaStream(
                    url = ingest.url,
                    type = MediaStreamType.VIDEO_AUDIO,
                    codec = null,
                    width = null,
                    height = null,
                    fps = null,
                    bitrate = null,
                    audioTrackName = null,
                    audioTrackLang = null,
                ),
            ),
            metadata = MediaMetadata.UNKNOWN.copy(
                title = "Live cast",
                uploader = null,
            ),
            isLive = true,
            isSeekable = false,
        )
    }
}
