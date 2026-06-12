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
}
