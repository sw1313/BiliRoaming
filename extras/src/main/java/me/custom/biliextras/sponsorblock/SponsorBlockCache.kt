package me.custom.biliextras.sponsorblock

import java.util.concurrent.ConcurrentHashMap

object SponsorBlockCache {
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
            cache[key] = it
        }
    }

    fun clear() {
        cache.clear()
    }
}
