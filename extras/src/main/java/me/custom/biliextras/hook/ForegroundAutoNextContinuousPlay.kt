package me.custom.biliextras.hook

import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.callMethodOrNull
import me.custom.biliextras.utils.callMethodOrNullAs
import me.custom.biliextras.utils.findClassOrNull

/**
 * Official foreground AI auto-next source: one ContinuousPlay call returns [Relate] list
 * with aid, uri, author.mid, dimension and tagName — no per-video View requests.
 */
internal object ForegroundAutoNextContinuousPlay {

    data class Candidate(
        val avid: Long,
        val uri: String?,
        val portrait: Boolean?,
        val upMid: Long?,
        val tagNames: Set<String>,
    )

    fun fetchCandidates(classLoader: ClassLoader, repo: Any, service: Any? = null): List<Candidate> {
        val anchor = UgcBackgroundPlayReflection.anchor(repo)
        val avid = anchor?.let {
            runCatching { it.javaClass.getMethod("getAvid").invoke(it) as Long }.getOrDefault(0L)
        } ?: service?.let { UgcBackgroundPlayReflection.currentAvid(it) } ?: 0L
        if (avid <= 0L) {
            Log.x("ForegroundAutoNext: ContinuousPlay skipped, no avid")
            return emptyList()
        }

        val mossClass = instance.viewMossClass ?: return emptyList()
        val reqClass = "com.bapis.bilibili.app.view.v1.ContinuousPlayReq".findClassOrNull(classLoader)
            ?: return emptyList()

        val builder = reqClass.getMethod("newBuilder").invoke(null)
        builder.javaClass.getMethod("setAid", Long::class.javaPrimitiveType).invoke(builder, avid)
        builder.javaClass.getMethod("setFrom", String::class.java).invoke(builder, "9501")
        anchor?.callMethodOrNullAs<String>("getTrackId")?.takeIf { it.isNotBlank() }?.let { trackId ->
            builder.javaClass.getMethod("setTrackid", String::class.java).invoke(builder, trackId)
        }
        anchor?.callMethodOrNullAs<String>("getSpmid")?.takeIf { it.isNotBlank() }?.let { spmid ->
            builder.javaClass.getMethod("setSpmid", String::class.java).invoke(builder, spmid)
        }
        anchor?.callMethodOrNullAs<String>("getFromSpmid")?.takeIf { it.isNotBlank() }?.let { fromSpmid ->
            builder.javaClass.getMethod("setFromSpmid", String::class.java).invoke(builder, fromSpmid)
        }
        anchor?.let {
            runCatching {
                it.javaClass.getMethod("getFromAutoPlay").invoke(it) as? Int
            }.getOrNull()?.let { autoplay ->
                builder.javaClass.getMethod("setAutoplay", Int::class.javaPrimitiveType).invoke(builder, autoplay)
            }
        }
        UgcBackgroundPlayReflection.sessionId(repo)?.let { sessionId ->
            builder.javaClass.getMethod("setSessionId", String::class.java).invoke(builder, sessionId)
        }
        runCatching {
            builder.javaClass.getMethod("setDisplayId", Long::class.javaPrimitiveType)
                .invoke(builder, UgcBackgroundPlayReflection.peekDisplayId(repo))
        }
        attachPlayerArgs(classLoader, builder)

        val req = builder.javaClass.getMethod("build").invoke(builder)
        val moss = mossClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val methodName = if (instance.useNewMossFunc) "executeContinuousPlay" else "continuousPlay"
        val reply = runCatching {
            mossClass.getMethod(methodName, reqClass).invoke(moss, req)
        }.onFailure {
            Log.s("ForegroundAutoNext: ContinuousPlay invoke failed: ${it.message}")
        }.getOrNull() ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        val relates = (reply.javaClass.getMethod("getRelatesList").invoke(reply) as? List<*>).orEmpty()
        Log.x("ForegroundAutoNext: ContinuousPlay avid=$avid relates=${relates.size}")
        return relates.mapNotNull { parseRelate(it) }
            .filter { !BlockChargingVideoHook.shouldBlockAvid(it.avid) }
    }

    private fun attachPlayerArgs(classLoader: ClassLoader, builder: Any) {
        val playerArgsClass = "com.bapis.bilibili.app.view.v1.PlayerArgs".findClassOrNull(classLoader) ?: return
        runCatching {
            val argsBuilder = playerArgsClass.getMethod("newBuilder").invoke(null)
            val args = argsBuilder.javaClass.getMethod("build").invoke(argsBuilder)
            builder.javaClass.getMethod("setPlayerArgs", playerArgsClass).invoke(builder, args)
        }
    }

    private fun parseRelate(relate: Any?): Candidate? {
        relate ?: return null
        val goto = relate.callMethodOrNullAs<String>("getGoto").orEmpty()
        if (goto.isNotBlank() && goto != "av") return null
        val aid = relate.callMethodOrNullAs<Long>("getAid") ?: return null
        if (aid <= 0L || BlockChargingVideoHook.shouldBlockAvid(aid)) return null
        if (relate.callMethodOrNull("hasCm") == true) return null
        val title = relate.callMethodOrNullAs<String>("getTitle").orEmpty()
        if (title.contains("充电专属")) return null

        val uri = relate.callMethodOrNullAs<String>("getUri")
            ?.takeIf { it.isNotBlank() }
            ?: relate.callMethodOrNullAs<String>("getJumpUrl")?.takeIf { it.isNotBlank() }
        val portrait = if (relate.callMethodOrNull("hasDimension") == true) {
            relate.callMethodOrNull("getDimension")?.let { ForegroundAutoNextVideoMeta.isPortraitDimension(it) }
        } else {
            null
        }
        val upMid = if (relate.callMethodOrNull("hasAuthor") == true) {
            relate.callMethodOrNull("getAuthor")?.callMethodOrNullAs<Long>("getMid")?.takeIf { it > 0L }
        } else {
            null
        }
        val tagName = relate.callMethodOrNullAs<String>("getTagName")?.trim().orEmpty()
        val tags = if (tagName.isNotEmpty()) setOf(tagName) else emptySet()
        return Candidate(aid, uri, portrait, upMid, tags)
    }
}
