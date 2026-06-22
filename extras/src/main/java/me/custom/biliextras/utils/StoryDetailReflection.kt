package me.custom.biliextras.utils

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Cached no-arg method lookup for [com.bilibili.video.story.StoryDetail] and related types. */
object StoryDetailReflection {
    private val noArgMethodCache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Method?>>()

    fun findNoArgMethod(type: Class<*>, name: String): Method? =
        noArgMethodCache.getOrPut(type) { ConcurrentHashMap() }
            .getOrPut(name) {
                runCatching {
                    type.getDeclaredMethod(name).apply { isAccessible = true }
                }.getOrNull()
            }

    fun invokeBool(method: Method?, item: Any): Boolean? = try {
        method?.invoke(item) as? Boolean
    } catch (_: Exception) {
        null
    }

    private data class StoryDetailAdMethods(
        val isAd: Method?,
        val isAdHardAndFly: Method?,
        val isAdImage: Method?,
        val isAdLive: Method?,
        val isAdLocal: Method?,
    )

    private val adMethodCache = ConcurrentHashMap<Class<*>, StoryDetailAdMethods>()

    fun isStoryAd(item: Any): Boolean {
        val methods = adMethodCache.getOrPut(item.javaClass) {
            val type = item.javaClass
            StoryDetailAdMethods(
                findNoArgMethod(type, "isAd"),
                findNoArgMethod(type, "isAdHardAndFly"),
                findNoArgMethod(type, "isAdImage"),
                findNoArgMethod(type, "isAdLive"),
                findNoArgMethod(type, "isAdLocal"),
            )
        }
        if (invokeBool(methods.isAd, item) == true) return true
        if (invokeBool(methods.isAdHardAndFly, item) == true) return true
        if (invokeBool(methods.isAdImage, item) == true) return true
        if (invokeBool(methods.isAdLive, item) == true) return true
        if (invokeBool(methods.isAdLocal, item) == true) return true
        return false
    }
}
