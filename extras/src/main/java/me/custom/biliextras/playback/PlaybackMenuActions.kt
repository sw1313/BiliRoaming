package me.custom.biliextras.playback

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import me.custom.biliextras.hook.DisableChapterProgressHook
import me.custom.biliextras.hook.ForegroundAutoNextHook
import me.custom.biliextras.hook.StoryBackgroundAutoNextHook
import me.custom.biliextras.hook.StoryUpBlockPrefs
import me.custom.biliextras.hook.StoryUpCardApi
import me.custom.biliextras.hook.StoryUpFilter
import me.custom.biliextras.sponsorblock.SponsorBlockMenuHost
import me.custom.biliextras.sponsorblock.SponsorBlockMenuUiFactory
import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.ePrefs
import java.util.concurrent.Executors

object PlaybackMenuActions {
    private val ioExecutor = Executors.newSingleThreadExecutor()

    @JvmStatic
    fun openForegroundAutoNextSubMenu(context: Context) {
        SponsorBlockMenuHost.hidePlayerSettingWidgetIfNeeded()
        Handler(Looper.getMainLooper()).post {
            PlaybackSubMenu.showForegroundAutoNext(context)
        }
    }

    @JvmStatic
    fun toggleForegroundAutoNext(checkedFlow: Any?) {
        val next = PlaybackRuntime.resolveToggleState(ForegroundAutoNextHook.PREF_KEY, false, checkedFlow)
        ePrefs.edit().putBoolean(ForegroundAutoNextHook.PREF_KEY, next).commit()
        PlaybackRuntime.applyForegroundAutoNext(next, ForegroundAutoNextHook.requireClassLoader())
        SponsorBlockMenuUiFactory.updateBooleanFlow(checkedFlow, next)
    }

    @JvmStatic
    fun openForegroundAutoNextScopes(context: Context) {
        PlaybackSubMenu.showForegroundScopes(context)
    }

    @JvmStatic
    fun openForegroundAutoNextPrefs(context: Context) {
        PlaybackSubMenu.showForegroundPrefs(context)
    }

    @JvmStatic
    fun openForegroundOrientationPicker(context: Context) {
        PlaybackSubMenu.showForegroundOrientationPicker(context)
    }

    @JvmStatic
    fun openForegroundUpPicker(context: Context) {
        PlaybackSubMenu.showForegroundUpPicker(context)
    }

    @JvmStatic
    fun openForegroundTagPicker(context: Context) {
        PlaybackSubMenu.showForegroundTagPicker(context)
    }

    @JvmStatic
    fun toggleStoryBackgroundAutoNext(checkedFlow: Any?) {
        val next = PlaybackRuntime.resolveToggleState(StoryBackgroundAutoNextHook.PREF_KEY, false, checkedFlow)
        ePrefs.edit().putBoolean(StoryBackgroundAutoNextHook.PREF_KEY, next).commit()
        PlaybackRuntime.applyStoryBackgroundAutoNext(next)
        SponsorBlockMenuUiFactory.updateBooleanFlow(checkedFlow, next)
    }

    @JvmStatic
    fun toggleMediaButtonControl(checkedFlow: Any?) {
        val next = PlaybackRuntime.resolveToggleState(StoryBackgroundAutoNextHook.MEDIA_BUTTON_KEY, false, checkedFlow)
        ePrefs.edit().putBoolean(StoryBackgroundAutoNextHook.MEDIA_BUTTON_KEY, next).commit()
        PlaybackRuntime.applyMediaButtonControl(next)
        SponsorBlockMenuUiFactory.updateBooleanFlow(checkedFlow, next)
    }

    @JvmStatic
    fun toggleDisableChapterProgress(checkedFlow: Any?) {
        val next = PlaybackRuntime.resolveToggleState(
            DisableChapterProgressHook.KEY_DISABLE_CHAPTER_PROGRESS,
            true,
            checkedFlow,
        )
        ePrefs.edit().putBoolean(DisableChapterProgressHook.KEY_DISABLE_CHAPTER_PROGRESS, next).commit()
        PlaybackRuntime.applyDisableChapterProgress(next)
        SponsorBlockMenuUiFactory.updateBooleanFlow(checkedFlow, next)
    }

    fun foregroundAutoNextStatusText(): String =
        if (ePrefs.getBoolean(ForegroundAutoNextHook.PREF_KEY, false)) {
            "已开启 · ${ForegroundAutoNextScopeSummary.short()}"
        } else {
            "已关闭"
        }

    @JvmStatic
    fun blockCurrentStoryUp(context: Context) {
        SponsorBlockMenuHost.hidePlayerSettingWidgetIfNeeded()
        val player = StoryBackgroundAutoNextHook.activeStoryPlayer
        if (player == null) {
            Log.toast("无法获取当前竖屏视频")
            return
        }
        val d1 = player.javaClass.declaredMethods.firstOrNull {
            it.name == "D1" && it.parameterCount == 0
        }?.apply { isAccessible = true }?.invoke(player) as? Int ?: -1
        val v1 = player.javaClass.methods.firstOrNull {
            it.name == "V1" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: run {
            Log.toast("无法获取当前竖屏视频")
            return
        }
        val item = runCatching { v1.invoke(player, d1.coerceAtLeast(0)) }.getOrNull()
        val mid = item?.let { StoryUpFilter.ownerMid(it) }
        if (mid == null || mid <= 0L) {
            Log.toast("当前视频没有 UP 信息")
            return
        }
        if (StoryUpBlockPrefs.containsMid(mid)) {
            Log.toast("已在屏蔽列表：${StoryUpCardApi.formatDisplay(mid)}")
            return
        }
        val storyName = item?.let { StoryUpFilter.ownerName(it) }
        ioExecutor.execute {
            val queriedName = StoryUpCardApi.fetchNameByMid(mid) ?: storyName
            val label = if (queriedName.isNullOrBlank()) mid.toString() else "$queriedName ($mid)"
            Handler(Looper.getMainLooper()).post {
                AlertDialog.Builder(context)
                    .setTitle("屏蔽当前UP")
                    .setMessage("确定屏蔽 $label 吗？\n屏蔽后竖屏流将不再推送该 UP 的视频。")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        StoryUpBlockPrefs.addMid(mid)
                        Log.toast("已屏蔽 $label")
                        StoryBackgroundAutoNextHook.mediaNext()
                    }
                    .show()
            }
        }
    }
}
