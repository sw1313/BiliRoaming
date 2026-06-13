package me.custom.biliextras.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import me.custom.biliextras.hook.DisableChapterProgressHook
import me.custom.biliextras.hook.ForegroundAutoNextHook
import me.custom.biliextras.hook.StoryBackgroundAutoNextHook
import me.custom.biliextras.sponsorblock.SponsorBlockMenuHost
import me.custom.biliextras.sponsorblock.SponsorBlockMenuUiFactory
import me.custom.biliextras.utils.ePrefs

object PlaybackMenuActions {

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
}
