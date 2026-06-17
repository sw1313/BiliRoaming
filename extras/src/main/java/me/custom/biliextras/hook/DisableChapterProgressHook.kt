package me.custom.biliextras.hook

import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.ePrefs
import me.custom.biliextras.utils.hookMethod

class DisableChapterProgressHook(mClassLoader: ClassLoader) : BaseHook(mClassLoader) {
    companion object {
        const val KEY_DISABLE_CHAPTER_PROGRESS = "disable_chapter_progress"

        fun isEnabled(): Boolean = ePrefs.getBoolean(KEY_DISABLE_CHAPTER_PROGRESS, true)

        @JvmStatic
        fun onPrefChanged(enabled: Boolean) {
            Log.trace { "DisableChapterProgress: pref -> $enabled" }
        }
    }

    override fun startHook() {
        hookChronosThumbnailInfo()
        hookChronosVideoViewPoint()
        hookChronosWatchPointDispatch()
        Log.trace { "DisableChapterProgress: started" }
    }

    private fun hookChronosThumbnailInfo() {
        val chronosClass = runCatching {
            mClassLoader.loadClass("tv.danmaku.biliplayerv2.service.interact.biz.model.ChronosThumbnailInfo")
        }.getOrNull()
        if (chronosClass == null) {
            Log.trace { "DisableChapterProgress: ChronosThumbnailInfo not found" }
            return
        }

        val listGetters = chronosClass.declaredMethods.filter { it.name == "getWatchPoints" && it.parameterCount == 0 }
        listGetters.forEach { getter ->
            getter.isAccessible = true
            getter.hookMethod { chain ->
                if (!isEnabled()) return@hookMethod chain.proceed()
                emptyList<Any>()
            }
        }

        val watchPointListSetters = chronosClass.declaredMethods.filter { m ->
            m.name == "setWatchPoints" && m.parameterTypes.contentEquals(arrayOf(List::class.java))
        }
        watchPointListSetters.forEach { setter ->
            setter.isAccessible = true
            setter.hookMethod { chain ->
                if (!isEnabled()) return@hookMethod chain.proceed()
                null
            }
        }

        Log.s("DisableChapterProgress: ChronosThumbnailInfo hooked ${listGetters.size} getters, ${watchPointListSetters.size} setters")
    }

    private fun hookChronosVideoViewPoint() {
        val videoViewPointClass = runCatching {
            mClassLoader.loadClass(
                "tv.danmaku.biliplayerv2.service.interact.biz.model.viewprogress.uniteviewprogress.VideoViewPoint"
            )
        }.getOrNull() ?: return

        videoViewPointClass.declaredMethods
            .filter { it.name == "getVideoPointList" && it.parameterCount == 0 }
            .forEach { getter ->
                getter.isAccessible = true
                getter.hookMethod { chain ->
                    if (!isEnabled()) return@hookMethod chain.proceed()
                    arrayListOf<Any>()
                }
            }

        videoViewPointClass.declaredMethods
            .filter { it.name == "getPointPermanent" && it.parameterCount == 0 }
            .forEach { getter ->
                getter.isAccessible = true
                getter.hookMethod { chain ->
                    if (!isEnabled()) return@hookMethod chain.proceed()
                    false
                }
            }

        Log.s("DisableChapterProgress: hooked VideoViewPoint video point source")
    }

    private fun hookChronosWatchPointDispatch() {
        val containerClass = runCatching {
            mClassLoader.loadClass("tv.danmaku.biliplayerv2.service.interact.biz.container.ChronosInteractContainer")
        }.getOrNull() ?: return
        containerClass.hookMethod("n0", List::class.java) { chain ->
            if (!isEnabled()) return@hookMethod chain.proceed()
            null
        }
        Log.s("DisableChapterProgress: hooked ChronosInteractContainer.n0")
    }
}
