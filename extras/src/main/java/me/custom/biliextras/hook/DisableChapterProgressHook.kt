package me.custom.biliextras.hook

import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.ePrefs
import me.custom.biliextras.utils.hookMethod
import kotlin.concurrent.thread

class DisableChapterProgressHook(mClassLoader: ClassLoader) : BaseHook(mClassLoader) {
    companion object {
        const val KEY_DISABLE_CHAPTER_PROGRESS = "disable_chapter_progress"
    }

    private val enabled: Boolean
        get() = ePrefs.getBoolean(KEY_DISABLE_CHAPTER_PROGRESS, true)

    override fun startHook() {
        thread(name = "BiliExtrasChapterProgressHook") {
            hookChronosThumbnailInfo()
            hookChronosVideoViewPoint()
            hookChronosWatchPointDispatch()
        }
        Log.x("DisableChapterProgress: started")
    }

    private fun hookChronosThumbnailInfo() {
        val chronosClass = runCatching {
            mClassLoader.loadClass("tv.danmaku.biliplayerv2.service.interact.biz.model.ChronosThumbnailInfo")
        }.getOrNull()
        if (chronosClass == null) {
            Log.x("DisableChapterProgress: ChronosThumbnailInfo not found")
            return
        }

        val listGetters = chronosClass.declaredMethods.filter { it.name == "getWatchPoints" && it.parameterCount == 0 }
        listGetters.forEach { getter ->
            getter.isAccessible = true
            getter.hookMethod { chain ->
                if (enabled) {
                    emptyList<Any>()
                } else {
                    chain.proceed()
                }
            }
        }

        val watchPointListSetters = chronosClass.declaredMethods.filter { m ->
            m.name == "setWatchPoints" && m.parameterTypes.contentEquals(arrayOf(List::class.java))
        }
        watchPointListSetters.forEach { setter ->
            setter.isAccessible = true
            setter.hookMethod { chain ->
                if (enabled) {
                    null
                } else {
                    chain.proceed()
                }
            }
        }

        Log.x(
            "DisableChapterProgress: ChronosThumbnailInfo hooked ${listGetters.size} getters, ${watchPointListSetters.size} setters"
        )
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
                    if (enabled) arrayListOf<Any>() else chain.proceed()
                }
            }

        videoViewPointClass.declaredMethods
            .filter { it.name == "getPointPermanent" && it.parameterCount == 0 }
            .forEach { getter ->
                getter.isAccessible = true
                getter.hookMethod { chain ->
                    if (enabled) false else chain.proceed()
                }
            }

        Log.x("DisableChapterProgress: hooked VideoViewPoint video point source")
    }

    private fun hookChronosWatchPointDispatch() {
        val containerClass = runCatching {
            mClassLoader.loadClass("tv.danmaku.biliplayerv2.service.interact.biz.container.ChronosInteractContainer")
        }.getOrNull() ?: return
        containerClass.hookMethod("n0", List::class.java) { chain ->
            if (enabled) {
                null
            } else {
                chain.proceed()
            }
        }
        Log.x("DisableChapterProgress: hooked ChronosInteractContainer.n0")
    }
}
