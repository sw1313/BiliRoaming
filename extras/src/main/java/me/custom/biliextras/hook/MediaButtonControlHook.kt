package me.custom.biliextras.hook

import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.ePrefs
import me.custom.biliextras.utils.findClassOrNull
import me.custom.biliextras.utils.hookMethod

/**
 * Media-button (Bluetooth headset / wired remote) previous & next video control.
 *
 * Bilibili routes every headset / lock-screen / notification skip into one
 * MediaSession owned by su1.g (PlayerHeadsetService). Its callback su1.g$b.z()
 * (onSkipToNext) / .A() (onSkipToPrevious) invoke the active playback handler
 * (com.bilibili.playerbizcommon.mediasession.a) .i() / .j(). Different playback
 * types install different handlers, so we intercept at the handler / director
 * level per type:
 *
 *  - Portrait (Story): handler is StoryPlayer$v whose i()/j() are no-ops
 *    ("ignore, story:single loop"). We hook them to drive the story pager
 *    next/previous (scenarios 1 foreground & 2 background).
 *  - Normal video (UGC/Theseus): handler -> DefaultMediaSessionPlayback.i()/j()
 *    -> UGCDirectorSerialOperationsService$a.switchToNext/Previous. For a
 *    foreground video with no next episode (non-collection or last episode), we
 *    open the next related video as a new page, reusing ForegroundAutoNextHook
 *    (scenario 3). Background (scenario 4) is left to the native AI playlist.
 *
 * Finally we force DefaultMediaSessionPlayback.v()/w() (hasNext/hasPrevious),
 * which only gate the advertised PlaybackState action bits (32 = skip-next,
 * 16 = skip-previous), so single-button line-control double/triple-tap and the
 * notification / lock-screen buttons are enabled, not only dedicated hardware
 * NEXT/PREV keys.
 */
class MediaButtonControlHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private companion object {
        const val PREF_KEY = "media_button_control"
        const val FOREGROUND_AUTO_NEXT_KEY = "foreground_auto_next"

        const val STORY_HANDLER = "com.bilibili.video.story.player.StoryPlayer\$v"
        const val UGC_DIRECTOR_INNER =
            "com.bilibili.ship.theseus.ugc.playercontainer.UGCDirectorSerialOperationsService\$a"
        const val UGC_DIRECTOR =
            "com.bilibili.ship.theseus.ugc.playercontainer.UGCDirectorSerialOperationsService"
        const val DEFAULT_MEDIA_SESSION_PLAYBACK =
            "com.bilibili.playerbizcommon.mediasession.DefaultMediaSessionPlayback"
    }

    override fun startHook() {
        if (!ePrefs.getBoolean(PREF_KEY, false)) return
        hookStorySkip()
        hookNormalNext()
        hookAdvertiseActions()
        Log.s("startHook: MediaButtonControl")
    }

    /**
     * Scenarios 1 & 2: portrait (Story) foreground / background. The native
     * StoryPlayer$v.i()/j() are no-ops; replace them with story pager navigation.
     */
    private fun hookStorySkip() {
        val handlerClass = STORY_HANDLER.findClassOrNull(mClassLoader) ?: run {
            Log.x("MediaButton: StoryPlayer\$v not found")
            return
        }
        handlerClass.hookMethod("i") {
            Log.x("MediaButton: story next")
            StoryBackgroundAutoNextHook.mediaNext()
            null
        }
        handlerClass.hookMethod("j") {
            Log.x("MediaButton: story previous")
            StoryBackgroundAutoNextHook.mediaPrevious()
            null
        }
        Log.x("MediaButton: hooked ${handlerClass.name}.i()/j()")
    }

    /**
     * Scenario 3: normal video foreground with no next episode -> open next
     * related video as a new page. Background (scenario 4) and collections with a
     * next part are left to native handling.
     */
    private fun hookNormalNext() {
        val innerClass = UGC_DIRECTOR_INNER.findClassOrNull(mClassLoader) ?: run {
            Log.x("MediaButton: UGCDirectorSerialOperationsService\$a not found")
            return
        }
        innerClass.hookMethod("switchToNext", Boolean::class.javaPrimitiveType) { chain ->
            if (!ePrefs.getBoolean(FOREGROUND_AUTO_NEXT_KEY, false)) {
                return@hookMethod chain.proceed()
            }
            val outer = outerDirector(chain.thisObject)
                ?: return@hookMethod chain.proceed()
            val repo = runCatching {
                outer.javaClass.getDeclaredField("b").apply { isAccessible = true }.get(outer)
            }.getOrNull() ?: return@hookMethod chain.proceed()

            val isBackground = runCatching {
                repo.javaClass.getMethod("w").invoke(repo) as Boolean
            }.getOrDefault(true)
            if (isBackground) {
                // Scenario 4: native AI background playlist already handles next.
                return@hookMethod chain.proceed()
            }

            val service = runCatching {
                outer.javaClass.getDeclaredField("c").apply { isAccessible = true }.get(outer)
            }.getOrNull() ?: return@hookMethod chain.proceed()

            if (ForegroundAutoNextHook.hasNextEpisode(service)) {
                Log.x("MediaButton: normal foreground next has episode, native")
                return@hookMethod chain.proceed()
            }

            Log.x("MediaButton: normal foreground next -> open related video")
            ForegroundAutoNextHook.openNextRelate(mClassLoader, service)
            null
        }
        Log.x("MediaButton: hooked ${innerClass.name}.switchToNext()")
    }

    private fun outerDirector(inner: Any): Any? {
        return inner.javaClass.declaredFields.firstOrNull {
            it.type.name == UGC_DIRECTOR
        }?.apply { isAccessible = true }?.get(inner)
    }

    /**
     * Force hasNext / hasPrevious so the MediaSession advertises ACTION_SKIP_TO_NEXT
     * (32) and ACTION_SKIP_TO_PREVIOUS (16). These methods are only consumed by
     * DefaultMediaSessionPlayback.g() to build the PlaybackState action mask, so
     * forcing them true merely enables the buttons; it never triggers a skip.
     */
    private fun hookAdvertiseActions() {
        val cls = DEFAULT_MEDIA_SESSION_PLAYBACK.findClassOrNull(mClassLoader) ?: run {
            Log.x("MediaButton: DefaultMediaSessionPlayback not found")
            return
        }
        cls.hookMethod("v") { true }
        cls.hookMethod("w") { true }
        Log.x("MediaButton: forcing skip actions advertised (v()/w())")
    }
}
