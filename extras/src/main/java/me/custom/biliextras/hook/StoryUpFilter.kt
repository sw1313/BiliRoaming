package me.custom.biliextras.hook

import me.custom.biliextras.utils.Log
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Filter Story feed items whose UP mid is in [StoryUpBlockPrefs]. */
object StoryUpFilter {
    private data class OwnerMethods(
        val getMid: Method?,
        val getName: Method?,
    )

    private val ownerMethodCache = ConcurrentHashMap<Class<*>, OwnerMethods>()

    fun enabled(): Boolean = StoryUpBlockPrefs.enabled() && StoryUpBlockPrefs.blockedMids().isNotEmpty()

    fun filterMutableList(list: MutableList<Any?>?) {
        if (!enabled() || list == null) return
        val before = list.size
        list.removeAll { item -> item != null && isBlockedUp(item) }
        val removed = before - list.size
        if (removed > 0) {
            Log.trace { "StoryUpFilter: removed $removed blocked UP item(s)" }
        }
    }

    fun filteredCopyIfChanged(list: List<Any?>?): ArrayList<Any?>? {
        if (!enabled() || list == null) return null
        val filtered = list.filterNot { item -> item != null && isBlockedUp(item) }
        val removed = list.size - filtered.size
        if (removed <= 0) return null
        Log.trace { "StoryUpFilter: removed $removed blocked UP item(s)" }
        return ArrayList(filtered)
    }

    fun isBlockedUp(item: Any): Boolean {
        val mid = ownerMid(item) ?: return false
        return StoryUpBlockPrefs.isBlocked(mid)
    }

    fun ownerMid(item: Any): Long? = runCatching {
        val owner = item.javaClass.getMethod("getOwner").invoke(item) ?: return@runCatching null
        (ownerMethods(owner.javaClass).getMid?.invoke(owner) as? Number)?.toLong()?.takeIf { it > 0L }
    }.getOrNull()

    fun ownerName(item: Any): String? = runCatching {
        val owner = item.javaClass.getMethod("getOwner").invoke(item) ?: return@runCatching null
        ownerMethods(owner.javaClass).getName?.invoke(owner) as? String
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun ownerMethods(type: Class<*>): OwnerMethods = ownerMethodCache.getOrPut(type) {
        OwnerMethods(
            getMid = runCatching { type.getDeclaredMethod("getMid").apply { isAccessible = true } }.getOrNull(),
            getName = runCatching { type.getDeclaredMethod("getName").apply { isAccessible = true } }.getOrNull(),
        )
    }
}
