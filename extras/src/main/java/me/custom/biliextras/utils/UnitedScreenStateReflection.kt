package me.custom.biliextras.utils

import android.app.Activity

/**
 * BFS lookup for [com.bilibili.ship.theseus.united.page.screenstate.ScreenStateRepo]-like objects
 * attached to UGC background-play services or their context.
 */
object UnitedScreenStateReflection {

    fun findScreenStateRepo(root: Any, maxDepth: Int = 4): Any? {
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Pair<Any, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (obj, depth) = queue.removeFirst()
            val id = System.identityHashCode(obj)
            if (!visited.add(id)) continue
            if (looksLikeScreenStateRepo(obj)) return obj
            if (depth >= maxDepth) continue
            for (field in obj.javaClass.declaredFields) {
                runCatching {
                    field.isAccessible = true
                    val value = field.get(obj) ?: return@runCatching
                    if (shouldTraverse(value)) {
                        queue.add(value to depth + 1)
                    }
                }
            }
        }
        return null
    }

    fun readFullscreen(repo: Any): Boolean {
        val state = repo.javaClass.getMethod("h").invoke(repo) ?: return false
        return state.javaClass.getMethod("b").invoke(state) as? Boolean ?: false
    }

    fun readPortrait(repo: Any): Boolean? {
        val state = repo.javaClass.getMethod("h").invoke(repo) ?: return null
        return state.javaClass.getMethod("e").invoke(state) as? Boolean
    }

    private fun looksLikeScreenStateRepo(obj: Any): Boolean = runCatching {
        val cls = obj.javaClass
        cls.getMethod("h")
        cls.getMethod("c")
        cls.getMethod("j", Any::class.java, Boolean::class.javaPrimitiveType)
        true
    }.getOrDefault(false)

    private fun shouldTraverse(value: Any): Boolean {
        if (value is String || value is Number || value is Boolean || value is Char) return false
        if (value is Class<*>) return false
        val name = value.javaClass.name
        if (name.startsWith("java.") || name.startsWith("kotlin.") ||
            name.startsWith("kotlinx.") || name.startsWith("android.") && value !is Activity
        ) {
            return false
        }
        return true
    }
}
