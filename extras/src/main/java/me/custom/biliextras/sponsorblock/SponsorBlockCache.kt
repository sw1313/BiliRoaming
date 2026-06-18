package me.custom.biliextras.sponsorblock

import java.util.concurrent.ConcurrentHashMap

object SponsorBlockCache {
    private const val MAX_ENTRIES = 48

    private data class CacheKey(
        val bvid: String,
        val cid: Long,
    )

    private val cache = ConcurrentHashMap<CacheKey, List<SponsorSegment>>()

    fun peek(bvid: String, cid: Long, categories: Set<String>): List<SponsorSegment>? {
        if (bvid.isBlank() || cid <= 0L) return null
        val key = CacheKey(bvid, cid)
        cache[key]?.let { return it }
        val disk = SponsorBlockDiskCache.load(bvid, cid) ?: return null
        cache[key] = disk
        return disk
    }

    fun getOrFetch(
        bvid: String,
        cid: Long,
        categories: Set<String>,
    ): Result<List<SponsorSegment>> {
        if (bvid.isBlank() || cid <= 0L) {
            return Result.success(emptyList())
        }
        val key = CacheKey(bvid, cid)
        cache[key]?.let { return Result.success(it) }
        SponsorBlockDiskCache.load(bvid, cid)?.let { disk ->
            cache[key] = disk
            return Result.success(disk)
        }
        return SponsorBlockApi.getSkipSegments(bvid, cid, categories).onSuccess { segments ->
            if (cache.size >= MAX_ENTRIES) {
                cache.keys.firstOrNull()?.let { cache.remove(it) }
            }
            cache[key] = segments
            SponsorBlockDiskCache.save(bvid, cid, segments)
        }
    }

    fun clear() {
        cache.clear()
    }

    fun invalidate(bvid: String, cid: Long) {
        cache.keys.removeIf { it.bvid == bvid && it.cid == cid }
        SponsorBlockDiskCache.remove(bvid, cid)
    }
}
