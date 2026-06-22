package me.custom.biliextras.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.ViewParent
import android.widget.AbsSeekBar
import android.widget.ProgressBar
import android.widget.SeekBar
import me.custom.biliextras.sponsorblock.SponsorBlockPrefs
import me.custom.biliextras.sponsorblock.SponsorBlockState
import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.hookMethod
import java.util.Collections
import java.util.WeakHashMap

class SponsorBlockProgressHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loggedClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val trackedSeekBars = Collections.newSetFromMap(WeakHashMap<ProgressBar, Boolean>())
    private var cachedDrawVersion = -1L
    private var cachedDrawSegments = emptyList<SponsorBlockState.SegmentView>()

    /** Official player timeline widgets only; volume/brightness sliders also extend SeekBar/ProgressBar. */
    private val videoSeekMarkers = listOf(
        "PlayerSeekWidget",
        "HighEnergySeekWidget",
        "StorySeekBar",
        "playerbizcommonv2.widget.seek",
    )

    override fun startHook() {
        if (!hookBeforeDrawThumb()) {
            hookBeforeDrawThumbFallback()
        }
        hookSeekBarAttachTracking()
        SponsorBlockState.addChangeListener(::requestSeekBarRefresh)
        Log.s("SponsorBlockProgress: hooked seek bar segment overlay (menu-only actions)")
    }

    /**
     * Official order (AbsSeekBar.onDraw): ProgressBar.onDraw (track layers) -> drawThumb (TV).
     * Paint sponsor ranges immediately before drawThumb so they sit above the bar and below TV.
     * Must NOT replace onDraw — that breaks thumb canvas state and can freeze the player.
     */
    private fun hookBeforeDrawThumb(): Boolean {
        val drawThumb = AbsSeekBar::class.java.declaredMethods.firstOrNull { method ->
            method.name == "drawThumb" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Canvas::class.java
        } ?: return false
        drawThumb.isAccessible = true
        drawThumb.hookMethod { chain ->
            val seekBar = chain.thisObject
            val canvas = chain.args.firstOrNull() as? Canvas
            if (seekBar is SeekBar && canvas != null && shouldDrawOnSeekBar(seekBar)) {
                trackedSeekBars.add(seekBar)
                drawSegmentsOnSeekBar(seekBar, canvas)
            }
            chain.proceed()
        }
        Log.trace { "SponsorBlockProgress: hooked AbsSeekBar.drawThumb" }
        return true
    }

    /** Older ROMs without drawThumb(): insert after ProgressBar.onDraw, still before thumb in same pass. */
    private fun hookBeforeDrawThumbFallback() {
        ProgressBar::class.java.hookMethod("onDraw", Canvas::class.java) { chain ->
            val progressBar = chain.thisObject as ProgressBar
            val canvas = chain.args.firstOrNull() as? Canvas
            val result = chain.proceed()
            if (progressBar is SeekBar && canvas != null && shouldDrawOnSeekBar(progressBar)) {
                trackedSeekBars.add(progressBar)
                drawSegmentsOnSeekBar(progressBar, canvas)
            }
            result
        }
        Log.trace { "SponsorBlockProgress: using ProgressBar.onDraw fallback" }
    }

    private fun shouldDrawOnSeekBar(seekBar: SeekBar): Boolean =
        SponsorBlockPrefs.enabled &&
            SponsorBlockState.showProgress &&
            SponsorBlockState.hasSegments &&
            isLikelyVideoProgress(seekBar)

    private fun hookSeekBarAttachTracking() {
        SeekBar::class.java.hookMethod("onAttachedToWindow") { chain ->
            val seekBar = chain.thisObject as SeekBar
            if (videoSeekMarkers.any { seekBar.javaClass.name.contains(it) }) {
                trackedSeekBars.add(seekBar)
            }
            chain.proceed()
        }
    }

    private fun requestSeekBarRefresh() {
        if (!SponsorBlockPrefs.enabled || !SponsorBlockState.showProgress) return
        mainHandler.post {
            trackedSeekBars.forEach { bar ->
                if (!bar.isShown) return@forEach
                bar.progressDrawable?.invalidateSelf()
                bar.postInvalidateOnAnimation()
            }
        }
    }

    private fun drawSegmentsOnSeekBar(seekBar: SeekBar, canvas: Canvas) {
        if (!seekBar.isShown || seekBar.width <= 80 || seekBar.height <= 0) return
        val video = SponsorBlockState.currentVideo ?: return
        val durationMs = resolveDurationMs(seekBar, video.durationMs) ?: return
        if (!matchesCurrentPlayback(seekBar, durationMs)) return
        val segments = segmentsForDraw()
        if (segments.isEmpty()) return

        if (loggedClasses.add(seekBar.javaClass)) {
            Log.trace {
                "SponsorBlockProgress: draw target=${seekBar.javaClass.name}, " +
                    "w=${seekBar.width}, h=${seekBar.height}, max=${seekBar.max}, duration=$durationMs, segments=${segments.size}"
            }
        }

        val (trackLeft, trackWidth) = seekBar.progressTrackMetrics()
        val barHeight = (seekBar.height.coerceAtMost(seekBar.dp(5))).coerceAtLeast(seekBar.dp(2)).toFloat()
        val top = ((seekBar.height - barHeight) / 2f).coerceAtLeast(0f)
        val bottom = top + barHeight
        segments.forEach { segment ->
            if (segment.endMs <= segment.startMs) return@forEach
            val startRatio = (segment.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
            val endRatio = (segment.endMs.toFloat() / durationMs).coerceIn(startRatio, 1f)
            val left = trackLeft + startRatio * trackWidth
            val right = trackLeft + endRatio * trackWidth
            if (right - left < 1f) return@forEach
            paint.color = segment.color
            paint.style = Paint.Style.FILL
            canvas.drawRect(left, top, right, bottom, paint)
        }
    }

    /** Official seek bars use [ProgressBar.getMax] as the timeline; arc duration can be longer (e.g. Story). */
    private fun resolveDurationMs(progressBar: ProgressBar, videoDurationMs: Long): Long? {
        val max = progressBar.max.toLong()
        if (isOfficialSeekBar(progressBar) && max > 1000L) return max
        if (max > 1000L && videoDurationMs > 0L &&
            kotlin.math.abs(max - videoDurationMs) > 1500L
        ) {
            return max
        }
        if (videoDurationMs > 0L) return videoDurationMs
        return max.takeIf { it > 0L }
    }

    private fun isLikelyVideoProgress(progressBar: ProgressBar): Boolean {
        if (progressBar.max <= 0) return false
        val cls = progressBar.javaClass.name
        if (progressBar is SeekBar) {
            if (videoSeekMarkers.any { cls.contains(it) }) return true
            if (isVolumeOrBrightnessBar(progressBar)) return false
            return false
        }
        if (isVolumeOrBrightnessBar(progressBar)) return false
        val width = progressBar.width
        val height = progressBar.height
        if (width < height * 6) return false
        val name = cls.lowercase()
        if (name.contains("loading") || name.contains("refresh")) return false
        return progressBar.progress >= 0
    }

    private fun isOfficialSeekBar(progressBar: ProgressBar): Boolean {
        val cls = progressBar.javaClass.name
        return cls.contains("playerbizcommonv2.widget.seek.v3") || cls.contains("StorySeekBar")
    }

    private fun isVolumeOrBrightnessBar(progressBar: ProgressBar): Boolean {
        var parent: ViewParent? = progressBar.parent
        var depth = 0
        while (parent != null && depth < 10) {
            val name = parent.javaClass.name
            if (name.contains("playerbizcommon.gesture") ||
                name.contains("BrightnessAndVolume") ||
                name.contains("BrightnessVolume")
            ) {
                return true
            }
            parent = parent.parent
            depth++
        }
        if (progressBar.height in 1..progressBar.dp(6)) return true
        return false
    }

    private fun matchesCurrentPlayback(progressBar: ProgressBar, durationMs: Long): Boolean {
        val max = progressBar.max.toLong()
        if (max <= 0L || durationMs <= 0L) return false
        if (kotlin.math.abs(max - durationMs) <= 1500L) return true
        if (isOfficialSeekBar(progressBar) && max > 1000L) return true
        if (max == 100L) {
            val playbackMs = SponsorBlockState.playbackPositionMs.takeIf { it >= 0L } ?: return false
            val progressMs = progressBar.progress.toLong() * durationMs / 100L
            return kotlin.math.abs(progressMs - playbackMs) <= 3000L
        }
        return false
    }

    /** Match AbsSeekBar thumb travel — full padding width makes segment ends sit past the TV icon. */
    private fun SeekBar.progressTrackMetrics(): Pair<Float, Float> {
        val paddingLeft = paddingLeft.toFloat()
        val paddingRight = paddingRight.toFloat()
        val thumbHalf = (thumb?.intrinsicWidth ?: 0) / 2f
        val trackLeft = paddingLeft + thumbHalf
        val trackWidth = (width - paddingLeft - paddingRight - 2f * thumbHalf).coerceAtLeast(1f)
        return trackLeft to trackWidth
    }

    private fun ProgressBar.dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun segmentsForDraw(): List<SponsorBlockState.SegmentView> {
        val version = SponsorBlockState.contentVersion()
        if (version == cachedDrawVersion) return cachedDrawSegments
        val segments = SponsorBlockState.segmentViews()
        cachedDrawVersion = version
        cachedDrawSegments = segments
        return segments
    }
}
