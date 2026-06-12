package me.custom.biliextras.sponsorblock

import android.os.Handler
import android.os.Looper

object SponsorBlockController {
    interface HookBridge {
        fun onEnabled()
        fun onDisabled()
        fun refetchCurrent()
        fun manualSkip(segment: SponsorBlockState.SegmentView)
        fun seekToSegment(segment: SponsorBlockState.SegmentView)
        fun readPlaybackPositionMs(): Long?
        fun currentSegments(): List<SponsorSegment>
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bridge: HookBridge? = null

    val isEnabled: Boolean
        get() = SponsorBlockPrefs.enabled

    val currentVideo: SponsorBlockState.Video?
        get() = SponsorBlockState.currentVideo

    val playbackPositionMs: Long
        get() = SponsorBlockState.playbackPositionMs

    val segments: List<SponsorBlockState.SegmentView>
        get() = SponsorBlockState.segmentViews()

    fun registerBridge(hookBridge: HookBridge) {
        bridge = hookBridge
    }

    fun setEnabled(value: Boolean) {
        if (SponsorBlockPrefs.enabled == value) return
        SponsorBlockPrefs.setEnabled(value)
        if (value) {
            bridge?.onEnabled()
        } else {
            bridge?.onDisabled()
        }
    }

    fun refetchCurrent(invalidateCache: Boolean = false) {
        if (invalidateCache) {
            currentVideo?.let { SponsorBlockCache.invalidate(it.bvid, it.cid) }
        }
        bridge?.refetchCurrent()
    }

    fun manualSkipSegment(segment: SponsorBlockState.SegmentView) {
        bridge?.manualSkip(segment)
    }

    fun seekToSegment(segment: SponsorBlockState.SegmentView) {
        bridge?.seekToSegment(segment)
    }

    fun readPlaybackPositionMs(): Long? = bridge?.readPlaybackPositionMs()

    fun findSegmentAtTimeMs(timeMs: Long): SponsorBlockState.SegmentView? =
        segments.firstOrNull { timeMs in it.startMs until it.endMs }

    fun vote(uuid: String, type: Int, category: String? = null, onComplete: ((Boolean) -> Unit)? = null) {
        if (uuid.isBlank()) {
            onComplete?.invoke(false)
            return
        }
        SponsorBlockBackground.submit {
            val ok = SponsorBlockApi.voteOnSponsorTime(uuid, type, category).getOrDefault(false)
            mainHandler.post {
                if (ok) {
                    currentVideo?.let { SponsorBlockCache.invalidate(it.bvid, it.cid) }
                    refetchCurrent()
                }
                onComplete?.invoke(ok)
            }
        }
    }

    fun submitSegment(
        startSec: Double,
        endSec: Double,
        category: String,
        onComplete: ((Boolean, String?) -> Unit)? = null,
    ) {
        submitSegments(listOf(SponsorBlockApi.SubmitSegment(startSec, endSec, category)), onComplete)
    }

    fun submitSegments(
        segments: List<SponsorBlockApi.SubmitSegment>,
        onComplete: ((Boolean, String?) -> Unit)? = null,
    ) {
        val video = currentVideo
        if (video == null || video.bvid.isBlank()) {
            onComplete?.invoke(false, "当前无视频")
            return
        }
        if (segments.isEmpty()) {
            onComplete?.invoke(false, "无片段可提交")
            return
        }
        SponsorBlockBackground.submit {
            val result = SponsorBlockApi.submitSegments(video.bvid, segments)
            mainHandler.post {
                result.onSuccess {
                    SponsorBlockCache.invalidate(video.bvid, video.cid)
                    refetchCurrent()
                    onComplete?.invoke(true, null)
                }.onFailure {
                    onComplete?.invoke(false, it.message)
                }
            }
        }
    }
}
