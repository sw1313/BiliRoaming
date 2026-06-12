package me.custom.biliextras.hook

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Cached field/method accessors for
 * [com.bilibili.ship.theseus.ugc.backgroundplay.UGCBackgroundPlayService] and its repos.
 */
internal object UgcBackgroundPlayReflection {
    private data class ServiceFields(
        val repo: Field,
        val episodeRepo: Field,
        val playbackRepo: Field,
        val context: Field,
        val methodP: Method?,
    )

    private data class RepoBackgroundAccess(
        val fieldO: Field,
        val getValue: Method,
    )

    private data class EpisodeRepoAccess(
        val methodG: Method,
        val methodJ: Method?,
    )

    private data class PlaybackRepoAccess(
        val methodW: Method,
    )

    private data class PageItemAccess(
        val methodA: Method,
    )

    private val serviceFields = ConcurrentHashMap<Class<*>, ServiceFields>()
    private val repoBackground = ConcurrentHashMap<Class<*>, RepoBackgroundAccess>()
    private val episodeRepoAccess = ConcurrentHashMap<Class<*>, EpisodeRepoAccess>()
    private val playbackRepoAccess = ConcurrentHashMap<Class<*>, PlaybackRepoAccess>()
    private val pageItemAccess = ConcurrentHashMap<Class<*>, PageItemAccess>()
    private val playbackCurrentAccess = ConcurrentHashMap<Class<*>, Method>()

    fun repo(service: Any): Any? = runCatching {
        fields(service).repo.get(service)
    }.getOrNull()

    fun context(service: Any): Any? = runCatching {
        fields(service).context.get(service)
    }.getOrNull()

    fun readRealBackground(repo: Any): Boolean = runCatching {
        val access = repoBackground.getOrPut(repo.javaClass) {
            val fieldO = repo.javaClass.getDeclaredField("o").apply { isAccessible = true }
            val sample = fieldO.get(repo)
            val getValue = sample.javaClass.getMethod("getValue")
            RepoBackgroundAccess(fieldO, getValue)
        }
        val flow = access.fieldO.get(repo)
        access.getValue.invoke(flow) as Boolean
    }.getOrDefault(false)

    fun currentPlaybackItem(service: Any): Any? = runCatching {
        val playbackRepo = fields(service).playbackRepo.get(service)
        val access = playbackRepoAccess.getOrPut(playbackRepo.javaClass) {
            PlaybackRepoAccess(playbackRepo.javaClass.getMethod("w"))
        }
        access.methodW.invoke(playbackRepo)
    }.getOrNull()

    fun currentAvid(service: Any): Long? = runCatching {
        val current = currentPlaybackItem(service) ?: return@runCatching null
        val methodB = playbackCurrentAccess.getOrPut(current.javaClass) {
            current.javaClass.getMethod("b")
        }
        methodB.invoke(current) as? Long
    }.getOrNull()

    fun classifyScope(service: Any): ForegroundAutoNextPrefs.Scope {
        val episodeRepo = runCatching {
            fields(service).episodeRepo.get(service)
        }.getOrNull() ?: return ForegroundAutoNextPrefs.Scope.SINGLE
        val playbackRepo = runCatching {
            fields(service).playbackRepo.get(service)
        }.getOrNull() ?: return ForegroundAutoNextPrefs.Scope.SINGLE
        val episode = episodeRepoAccess.getOrPut(episodeRepo.javaClass) {
            EpisodeRepoAccess(
                episodeRepo.javaClass.getMethod("g"),
                episodeRepo.javaClass.declaredMethods.firstOrNull {
                    it.name == "j" && it.parameterCount == 1
                }?.apply { isAccessible = true },
            )
        }
        val playback = playbackRepoAccess.getOrPut(playbackRepo.javaClass) {
            PlaybackRepoAccess(playbackRepo.javaClass.getMethod("w"))
        }
        val current = runCatching { playback.methodW.invoke(playbackRepo) }.getOrNull()
        val listSize = runCatching {
            (episode.methodG.invoke(episodeRepo) as? List<*>)?.size ?: 1
        }.getOrDefault(1)
        val hasNext = runCatching {
            episode.methodJ?.invoke(episodeRepo, current) != null
        }.getOrDefault(false)
        return when {
            hasNext -> ForegroundAutoNextPrefs.Scope.COLLECTION_MIDDLE
            listSize > 1 -> ForegroundAutoNextPrefs.Scope.COLLECTION_LAST
            else -> ForegroundAutoNextPrefs.Scope.SINGLE
        }
    }

    fun invokeServiceP(service: Any): Any? = runCatching {
        fields(service).methodP?.invoke(service)
    }.getOrNull()

    fun rawAiAvids(service: Any): List<Long> = runCatching {
        val repo = repo(service) ?: return@runCatching emptyList<Long>()
        val size = repo.javaClass.getMethod("m").invoke(repo) as Int
        val cur = repo.javaClass.getMethod("q").invoke(repo) as Int
        if (size <= 0 || cur + 1 >= size) return@runCatching emptyList<Long>()
        val methodO = repo.javaClass.getMethod("o", Int::class.javaPrimitiveType)
        ((cur + 1) until size).mapNotNull { idx ->
            val item = methodO.invoke(repo, idx) ?: return@mapNotNull null
            val methodA = pageItemAccess.getOrPut(item.javaClass) {
                PageItemAccess(item.javaClass.getMethod("a"))
            }.methodA
            methodA.invoke(item) as? Long
        }.filter { it > 0L }
    }.getOrDefault(emptyList())

    private fun fields(service: Any): ServiceFields =
        serviceFields.getOrPut(service.javaClass) {
            val cls = service.javaClass
            ServiceFields(
                repo = cls.getDeclaredField("b").apply { isAccessible = true },
                episodeRepo = cls.getDeclaredField("c").apply { isAccessible = true },
                playbackRepo = cls.getDeclaredField("d").apply { isAccessible = true },
                context = cls.getDeclaredField("n").apply { isAccessible = true },
                methodP = cls.declaredMethods.firstOrNull { it.name == "p" && it.parameterCount == 0 }
                    ?.apply { isAccessible = true },
            )
        }
}
