package me.custom.biliextras.sponsorblock

import java.util.concurrent.ConcurrentHashMap

object SponsorBlockCache {
    private const val MAX_ENTRIES = 48

    private data class CacheKey(
        val bvid: String,
        val cid: Long,
        val categories: String,
    )

    private val cache = ConcurrentHashMap<CacheKey, List<SponsorSegment>>()

    fun getOrFetch(
        bvid: String,
        cid: Long,
        categories: Set<String>,
    ): Result<List<SponsorSegment>> {
        val key = CacheKey(bvid, cid, categories.sorted().joinToString(","))
        cache[key]?.let { return Result.success(it) }
        return SponsorBlockApi.getSkipSegments(bvid, cid, categories).onSuccess {
            if (cache.size >= MAX_ENTRIES) {
                cache.keys.firstOrNull()?.let { cache.remove(it) }
            }
            cache[key] = it
        }
    }

    fun clear() {
        cache.clear()
    }

    fun invalidate(bvid: String, cid: Long) {
        cache.keys.removeIf { it.bvid == bvid && it.cid == cid }
    }
}
