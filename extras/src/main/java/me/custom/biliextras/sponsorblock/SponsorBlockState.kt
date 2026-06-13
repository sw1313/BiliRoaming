package me.custom.biliextras.sponsorblock

import java.util.concurrent.ConcurrentHashMap
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
        val uuid: String = "",
        val actionType: String = "skip",
    )

    private val version = AtomicLong(0L)
    private val segments = CopyOnWriteArrayList<SegmentView>()
    /** Local vote state keyed by segment UUID (1=up, 0=down). Cleared on video reset. */
    private val userVotesByUuid = ConcurrentHashMap<String, Int>()
    @Volatile
    private var cachedVersion = -1L
    @Volatile
    private var cachedSegmentViews: List<SegmentView>? = null

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

    fun recordUserVote(uuid: String, type: Int) {
        if (uuid.isBlank()) return
        if (type == 20) {
            userVotesByUuid.remove(uuid)
        } else {
            userVotesByUuid[uuid] = type
        }
    }

    fun userVoteLabel(uuid: String): String? = when (userVotesByUuid[uuid]) {
        1 -> "已赞成"
        0 -> "已反对"
        else -> null
    }

    fun voteActionSubtitle(uuid: String, actionVoteType: Int): String {
        val current = userVoteLabel(uuid)
        return when (actionVoteType) {
            1 -> if (current == "已赞成") "已赞成" else ""
            0 -> if (current == "已反对") "已反对" else ""
            20 -> current?.let { "当前：$it" } ?: ""
            else -> ""
        }
    }

    fun reset(video: Video?) {
        currentVideo = video
        segments.clear()
        userVotesByUuid.clear()
        hasSegments = false
        playbackPositionMs = -1L
        lastPositionUpdateTimeMs = 0L
        cachedSegmentViews = null
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
                    uuid = segment.uuid,
                    actionType = segment.actionType,
                )
            }
        }
        hasSegments = segments.isNotEmpty()
        showProgress = SponsorBlockPrefs.showProgress
        cachedSegmentViews = null
        version.incrementAndGet()
    }

    fun updatePlaybackPosition(positionMs: Long) {
        if (positionMs == playbackPositionMs) return
        val now = System.currentTimeMillis()
        if (now - lastPositionUpdateTimeMs < 250L) return
        playbackPositionMs = positionMs
        lastPositionUpdateTimeMs = now
    }

    fun segmentViews(): List<SegmentView> {
        val v = version.get()
        val cached = cachedSegmentViews
        if (cached != null && cachedVersion == v) return cached
        val copy = segments.toList()
        cachedSegmentViews = copy
        cachedVersion = v
        return copy
    }

    fun contentVersion(): Long = version.get()

    fun snapshot(): Pair<Long, List<SegmentView>> = contentVersion() to segmentViews()
}
