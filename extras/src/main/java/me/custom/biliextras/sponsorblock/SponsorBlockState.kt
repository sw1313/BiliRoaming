package me.custom.biliextras.sponsorblock

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

object SponsorBlockState {
    data class Video(
        val bvid: String,
        val cid: Long,
        val durationMs: Long,
    )

    data class SegmentView(
        val startMs: Long,
        val endMs: Long,
        val category: String,
        val color: Int,
        val mode: SponsorBlockCategory.SkipMode,
    )

    private val version = AtomicLong(0L)
    private val segments = CopyOnWriteArrayList<SegmentView>()

    @Volatile
    var currentVideo: Video? = null
        private set

    @Volatile
    var hasSegments: Boolean = false
        private set

    @Volatile
    var showProgress: Boolean = SponsorBlockPrefs.showProgress

    @Volatile
    var playbackPositionMs: Long = -1L
        private set

    @Volatile
    var lastPositionUpdateTimeMs: Long = 0L
        private set

    fun isPositionFresh(maxAgeMs: Long = 2000L): Boolean {
        if (playbackPositionMs < 0L) return false
        return System.currentTimeMillis() - lastPositionUpdateTimeMs <= maxAgeMs
    }

    fun invalidatePosition() {
        playbackPositionMs = -1L
        lastPositionUpdateTimeMs = 0L
    }

    fun reset(video: Video?) {
        currentVideo = video
        segments.clear()
        hasSegments = false
        playbackPositionMs = -1L
        lastPositionUpdateTimeMs = 0L
        version.incrementAndGet()
    }

    fun update(video: Video, sponsorSegments: List<SponsorSegment>) {
        currentVideo = video
        segments.clear()
        sponsorSegments.mapNotNullTo(segments) { segment ->
            val mode = SponsorBlockPrefs.modeOf(segment.category)
            if (mode == SponsorBlockCategory.SkipMode.Disabled) {
                null
            } else {
                SegmentView(
                    startMs = (segment.start * 1000).toLong().coerceAtLeast(0L),
                    endMs = (segment.end * 1000).toLong().coerceAtLeast(0L),
                    category = segment.category,
                    color = SponsorBlockPrefs.colorOf(segment.category),
                    mode = mode,
                )
            }
        }
        hasSegments = segments.isNotEmpty()
        showProgress = SponsorBlockPrefs.showProgress
        version.incrementAndGet()
    }

    fun updatePlaybackPosition(positionMs: Long) {
        playbackPositionMs = positionMs
        lastPositionUpdateTimeMs = System.currentTimeMillis()
    }

    fun snapshot(): Pair<Long, List<SegmentView>> = version.get() to segments.toList()
}
