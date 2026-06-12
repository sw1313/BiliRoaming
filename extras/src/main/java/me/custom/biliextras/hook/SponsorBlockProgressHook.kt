package me.custom.biliextras.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.view.ViewParent
import android.widget.ProgressBar
import android.widget.SeekBar
import android.view.MotionEvent
import me.custom.biliextras.sponsorblock.SponsorBlockCategory
import me.custom.biliextras.sponsorblock.SponsorBlockController
import me.custom.biliextras.sponsorblock.SponsorBlockPrefs
import me.custom.biliextras.sponsorblock.SponsorBlockSegmentActionDialog
import me.custom.biliextras.sponsorblock.SponsorBlockState
import me.custom.biliextras.sponsorblock.SponsorBlockSubmitDialog
import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.hookMethod
import java.util.Collections
import java.util.WeakHashMap

class SponsorBlockProgressHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loggedClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
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
        ProgressBar::class.java.hookMethod("onDraw", Canvas::class.java) { chain ->
            val result = chain.proceed()
            if (SponsorBlockPrefs.enabled && SponsorBlockState.showProgress && SponsorBlockState.hasSegments) {
                drawSegments(chain.thisObject as? ProgressBar, chain.args.firstOrNull() as? Canvas)
            }
            result
        }
        SeekBar::class.java.hookMethod("onTouchEvent", android.view.MotionEvent::class.java) { chain ->
            val result = chain.proceed()
            if (SponsorBlockPrefs.enabled) {
                handleSeekBarTouch(chain.thisObject as? SeekBar, chain.args.firstOrNull() as? android.view.MotionEvent)
            }
            result
        }
        Log.x("SponsorBlockProgress: hooked ProgressBar.onDraw + SeekBar.onTouchEvent")
    }

    private fun drawSegments(progressBar: ProgressBar?, canvas: Canvas?) {
        if (progressBar == null || canvas == null) return
        if (!progressBar.isShown || progressBar.width <= 80 || progressBar.height <= 0) return
        if (!isLikelyVideoProgress(progressBar)) return
        if (!SponsorBlockState.isPositionFresh()) return
        val video = SponsorBlockState.currentVideo ?: return
        val durationMs = video.durationMs.takeIf { it > 0 } ?: progressBar.max.toLong().takeIf { it > 0 } ?: return
        if (!matchesCurrentPlayback(progressBar, durationMs)) return
        val segments = segmentsForDraw()
        if (segments.isEmpty()) return

        if (loggedClasses.add(progressBar.javaClass)) {
            Log.x(
                "SponsorBlockProgress: draw target=${progressBar.javaClass.name}, " +
                    "w=${progressBar.width}, h=${progressBar.height}, max=${progressBar.max}, duration=$durationMs, segments=${segments.size}",
            )
        }

        val barHeight = (progressBar.height.coerceAtMost(progressBar.dp(5))).coerceAtLeast(progressBar.dp(2)).toFloat()
        val top = ((progressBar.height - barHeight) / 2f).coerceAtLeast(0f)
        val bottom = top + barHeight
        val width = progressBar.width.toFloat()
        segments.forEach { segment ->
            if (segment.endMs <= segment.startMs) return@forEach
            val left = (segment.startMs.toFloat() / durationMs * width).coerceIn(0f, width)
            val right = (segment.endMs.toFloat() / durationMs * width).coerceIn(left, width)
            if (right - left < 1f) return@forEach
            paint.color = segment.color
            canvas.drawRect(left, top, right, bottom, paint)
        }
    }

    private fun isLikelyVideoProgress(progressBar: ProgressBar): Boolean {
        if (progressBar.max <= 0) return false
        val cls = progressBar.javaClass.name
        if (progressBar is SeekBar) {
            // Whitelist first: Story 竖屏/横屏都用 StorySeekBar，不受细条启发式影响。
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
        // Volume overlay uses a very thin seek bar (~2dp); player timeline is much taller.
        if (progressBar.height in 1..progressBar.dp(6)) return true
        return false
    }

    private fun matchesCurrentPlayback(progressBar: ProgressBar, durationMs: Long): Boolean {
        val max = progressBar.max.toLong()
        if (max <= 0L || durationMs <= 0L) return false
        if (kotlin.math.abs(max - durationMs) <= 1500L) return true
        if (max == 100L) {
            val playbackMs = SponsorBlockState.playbackPositionMs.takeIf { it >= 0L } ?: return false
            val progressMs = progressBar.progress.toLong() * durationMs / 100L
            return kotlin.math.abs(progressMs - playbackMs) <= 3000L
        }
        return false
    }

    private fun ProgressBar.dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun handleSeekBarTouch(seekBar: SeekBar?, event: MotionEvent?) {
        if (seekBar == null || event == null) return
        if (event.action != MotionEvent.ACTION_UP) return
        if (!isLikelyVideoProgress(seekBar)) return
        if (!SponsorBlockState.hasSegments) return
        val video = SponsorBlockState.currentVideo ?: return
        val durationMs = video.durationMs.takeIf { it > 0 } ?: seekBar.max.toLong().takeIf { it > 0 } ?: return
        if (!matchesCurrentPlayback(seekBar, durationMs)) return
        val touchX = event.x.coerceIn(0f, seekBar.width.toFloat())
        val timeMs = (touchX / seekBar.width * durationMs).toLong()
        val segment = SponsorBlockController.findSegmentAtTimeMs(timeMs) ?: return
        when (segment.mode) {
            SponsorBlockCategory.SkipMode.Manual -> SponsorBlockController.manualSkipSegment(segment)
            SponsorBlockCategory.SkipMode.Disabled -> return
            else -> SponsorBlockSegmentActionDialog.show(seekBar.context, segment)
        }
    }

    fun handleSeekBarLongPress(seekBar: SeekBar) {
        if (!SponsorBlockPrefs.enabled) return
        if (!isLikelyVideoProgress(seekBar)) return
        SponsorBlockSubmitDialog.show(seekBar.context)
    }

    private fun segmentsForDraw(): List<SponsorBlockState.SegmentView> {
        val version = SponsorBlockState.contentVersion()
        if (version == cachedDrawVersion) return cachedDrawSegments
        val segments = SponsorBlockState.segmentViews()
        cachedDrawVersion = version
        cachedDrawSegments = segments
        return segments
    }
}
