package me.custom.biliextras.hook

import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.ePrefs
import me.custom.biliextras.utils.findClassOrNull
import me.custom.biliextras.utils.hookMethod
import java.util.concurrent.atomic.AtomicReference

/**
 * Media-button (Bluetooth headset / wired remote) previous & next video control.
 */
class MediaButtonControlHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    companion object {
        private const val PREF_KEY = "media_button_control"
        private const val FOREGROUND_AUTO_NEXT_KEY = "foreground_auto_next"
        private const val ACTION_SKIP_TO_PREVIOUS = 16
        private const val ACTION_SKIP_TO_NEXT = 32

        private const val STORY_HANDLER = "com.bilibili.video.story.player.StoryPlayer\$v"
        private const val UGC_DIRECTOR_INNER =
            "com.bilibili.ship.theseus.ugc.playercontainer.UGCDirectorSerialOperationsService\$a"
        private const val UGC_DIRECTOR =
            "com.bilibili.ship.theseus.ugc.playercontainer.UGCDirectorSerialOperationsService"
        private const val DEFAULT_MEDIA_SESSION_PLAYBACK =
            "com.bilibili.playerbizcommon.mediasession.DefaultMediaSessionPlayback"

        private val liveClassLoader = AtomicReference<ClassLoader>()

        fun isEnabled(): Boolean = ePrefs.getBoolean(PREF_KEY, false)

        @JvmStatic
        fun onPrefChanged(enabled: Boolean) {
            Log.x("MediaButton: pref -> $enabled")
            liveClassLoader.get()?.let { loader ->
                refreshMediaSessionActions(loader)
            }
        }

        private fun refreshMediaSessionActions(classLoader: ClassLoader) {
            val cls = DEFAULT_MEDIA_SESSION_PLAYBACK.findClassOrNull(classLoader) ?: return
            val refresh = cls.declaredMethods.firstOrNull { method ->
                method.parameterCount == 0 &&
                    method.name in setOf("g", "h", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u")
            } ?: return
            runCatching {
                val instance = findMediaSessionPlaybackInstance(cls) ?: return
                refresh.isAccessible = true
                refresh.invoke(instance)
                Log.x("MediaButton: refreshed session via ${cls.simpleName}.${refresh.name}()")
            }.onFailure {
                Log.x("MediaButton: session refresh failed: ${it.message}")
            }
        }

        private fun findMediaSessionPlaybackInstance(cls: Class<*>): Any? {
            return runCatching {
                cls.declaredFields.firstOrNull { field ->
                    java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                        field.type == cls
                }?.apply { isAccessible = true }?.get(null)
            }.getOrNull()
        }

        private fun augmentSkipActions(result: Any?): Any? {
            if (!isEnabled()) return result
            return when (result) {
                is Int -> result or ACTION_SKIP_TO_PREVIOUS or ACTION_SKIP_TO_NEXT
                is Long -> result or ACTION_SKIP_TO_PREVIOUS.toLong() or ACTION_SKIP_TO_NEXT.toLong()
                else -> result
            }
        }

        internal fun setLiveClassLoader(classLoader: ClassLoader) {
            liveClassLoader.set(classLoader)
        }
    }

    override fun startHook() {
        setLiveClassLoader(mClassLoader)
        hookStorySkip()
        hookNormalNext()
        hookAdvertiseActions()
        Log.s("startHook: MediaButtonControl")
    }

    private fun hookStorySkip() {
        val handlerClass = STORY_HANDLER.findClassOrNull(mClassLoader) ?: run {
            Log.x("MediaButton: StoryPlayer\$v not found")
            return
        }
        handlerClass.hookMethod("i") {
            if (!isEnabled()) return@hookMethod null
            Log.x("MediaButton: story next")
            StoryBackgroundAutoNextHook.mediaNext()
            null
        }
        handlerClass.hookMethod("j") {
            if (!isEnabled()) return@hookMethod null
            Log.x("MediaButton: story previous")
            StoryBackgroundAutoNextHook.mediaPrevious()
            null
        }
        Log.x("MediaButton: hooked ${handlerClass.name}.i()/j()")
    }

    private fun hookNormalNext() {
        val innerClass = UGC_DIRECTOR_INNER.findClassOrNull(mClassLoader) ?: run {
            Log.x("MediaButton: UGCDirectorSerialOperationsService\$a not found")
            return
        }
        innerClass.hookMethod("switchToNext", Boolean::class.javaPrimitiveType) { chain ->
            if (!isEnabled()) {
                return@hookMethod chain.proceed()
            }
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
                return@hookMethod chain.proceed()
            }

            val service = runCatching {
                outer.javaClass.getDeclaredField("c").apply { isAccessible = true }.get(outer)
            }.getOrNull() ?: return@hookMethod chain.proceed()

            if (!ForegroundAutoNextPrefs.shouldApplyAiAutoNext(service)) {
                Log.x("MediaButton: normal foreground next scope disabled, native")
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

    private fun hookAdvertiseActions() {
        val cls = DEFAULT_MEDIA_SESSION_PLAYBACK.findClassOrNull(mClassLoader) ?: run {
            Log.x("MediaButton: DefaultMediaSessionPlayback not found")
            return
        }
        cls.hookMethod("v") { isEnabled() }
        cls.hookMethod("w") { isEnabled() }
        hookPlaybackActionMask(cls, "g")
        hookPlaybackActionMask(cls, "f")
        Log.x("MediaButton: hooked skip action advertisement")
    }

    private fun hookPlaybackActionMask(cls: Class<*>, methodName: String) {
        val method = cls.declaredMethods.firstOrNull {
            it.name == methodName && it.parameterCount == 0
        } ?: return
        method.hookMethod { chain ->
            augmentSkipActions(chain.proceed())
        }
    }
}
