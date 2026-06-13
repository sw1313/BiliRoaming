package me.custom.biliextras.playback

import me.custom.biliextras.hook.DisableChapterProgressHook
import me.custom.biliextras.hook.ForegroundAutoNextHook
import me.custom.biliextras.hook.MediaButtonControlHook
import me.custom.biliextras.hook.StoryBackgroundAutoNextHook
import me.custom.biliextras.sponsorblock.SponsorBlockMenuUiFactory
import me.custom.biliextras.utils.ePrefs

/**
 * Applies in-player menu toggle side effects immediately (same pattern as [me.custom.biliextras.sponsorblock.SponsorBlockController]).
 */
object PlaybackRuntime {

    @JvmStatic
    fun resolveToggleState(key: String, default: Boolean, checkedFlow: Any?): Boolean {
        val fromPref = ePrefs.getBoolean(key, default)
        val fromFlow = SponsorBlockMenuUiFactory.readBooleanFlow(checkedFlow)
        return when {
            fromFlow != null && fromFlow != fromPref -> fromFlow
            else -> !fromPref
        }
    }

    @JvmStatic
    fun applyForegroundAutoNext(enabled: Boolean, classLoader: ClassLoader) {
        if (!enabled) {
            ForegroundAutoNextHook.abortActivation(classLoader, "pref disabled")
        }
    }

    @JvmStatic
    fun applyStoryBackgroundAutoNext(enabled: Boolean) {
        StoryBackgroundAutoNextHook.liveInstance?.onAutoNextPrefChanged(enabled)
    }

    @JvmStatic
    fun applyMediaButtonControl(enabled: Boolean) {
        MediaButtonControlHook.onPrefChanged(enabled)
    }

    @JvmStatic
    fun applyDisableChapterProgress(enabled: Boolean) {
        DisableChapterProgressHook.onPrefChanged(enabled)
    }
}
