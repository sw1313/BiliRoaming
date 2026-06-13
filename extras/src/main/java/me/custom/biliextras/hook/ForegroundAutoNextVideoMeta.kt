package me.custom.biliextras.hook

import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.utils.callMethodOrNull
import me.custom.biliextras.utils.callMethodOrNullAs
import me.custom.biliextras.utils.findClassOrNull
import java.util.concurrent.ConcurrentHashMap

internal object ForegroundAutoNextVideoMeta {
    data class Meta(
        val upMid: Long?,
        val tagNames: Set<String>,
        val portrait: Boolean?,
    )

    private val cacheByAvid = ConcurrentHashMap<Long, Meta>()

    fun cached(avid: Long): Meta? = cacheByAvid[avid]

    fun putCached(avid: Long, meta: Meta) {
        if (avid > 0L) cacheByAvid[avid] = meta
    }

    fun cacheFromViewUniteReply(avid: Long, reply: Any) {
        if (avid <= 0L) return
        val upMid = reply.callMethodOrNull("getOwner")?.callMethodOrNullAs<Long>("getMid")?.takeIf { it > 0L }
        val tags = extractTagsFromViewUniteReply(reply)
        val portrait = reply.callMethodOrNull("getArc")?.let { arc ->
            if (arc.callMethodOrNull("hasDimension") == true) {
                arc.callMethodOrNull("getDimension")?.let(::isPortraitDimension)
            } else {
                null
            }
        }
        putCached(avid, Meta(upMid, tags, portrait))
    }

    fun clearCache() {
        cacheByAvid.clear()
    }

    fun fetch(classLoader: ClassLoader, avid: Long): Meta? {
        if (avid <= 0L) return null
        cached(avid)?.let { return it }
        val fetched = fetchViewUnite(classLoader, avid) ?: fetchViewV1(classLoader, avid)
        if (fetched != null) putCached(avid, fetched)
        return fetched
    }

    private fun fetchViewUnite(classLoader: ClassLoader, avid: Long): Meta? = runCatching {
        val mossClass = "com.bapis.bilibili.app.viewunite.v1.ViewMoss".findClassOrNull(classLoader) ?: return@runCatching null
        val reqClass = "com.bapis.bilibili.app.viewunite.v1.ViewReq".findClassOrNull(classLoader) ?: return@runCatching null
        val builder = reqClass.getMethod("newBuilder").invoke(null)
        builder.javaClass.getMethod("setAid", Long::class.javaPrimitiveType).invoke(builder, avid)
        val req = builder.javaClass.getMethod("build").invoke(builder)
        val moss = mossClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val methodName = if (instance.useNewMossFunc) "executeView" else "view"
        val reply = mossClass.getMethod(methodName, reqClass).invoke(moss, req) ?: return@runCatching null
        val upMid = reply.callMethodOrNull("getOwner")?.callMethodOrNullAs<Long>("getMid")?.takeIf { it > 0L }
        val tags = extractTagsFromViewUniteReply(reply)
        val portrait = reply.callMethodOrNull("getArc")?.let { arc ->
            if (arc.callMethodOrNull("hasDimension") == true) {
                arc.callMethodOrNull("getDimension")?.let(::isPortraitDimension)
            } else {
                null
            }
        }
        Meta(upMid, tags, portrait)
    }.getOrNull()

    private fun fetchViewV1(classLoader: ClassLoader, avid: Long): Meta? = runCatching {
        val mossClass = instance.viewMossClass ?: return@runCatching null
        val reqClass = instance.viewReqClass ?: return@runCatching null
        val builder = reqClass.getMethod("newBuilder").invoke(null)
        builder.javaClass.getMethod("setAid", Long::class.javaPrimitiveType).invoke(builder, avid)
        val req = builder.javaClass.getMethod("build").invoke(builder)
        val moss = mossClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val methodName = if (instance.useNewMossFunc) "executeView" else "view"
        val reply = mossClass.getMethod(methodName, reqClass).invoke(moss, req) ?: return@runCatching null
        @Suppress("UNCHECKED_CAST")
        val staff = (reply.callMethodOrNull("getStaffList") as? List<*>)?.firstOrNull()
        val upMid = staff?.callMethodOrNullAs<Long>("getMid")?.takeIf { it > 0L }
        @Suppress("UNCHECKED_CAST")
        val tagList = reply.callMethodOrNull("getTagList") as? List<*>
        val tags = tagList.orEmpty().mapNotNull { tag ->
            tag?.callMethodOrNullAs<String>("getName")?.trim()?.takeIf { it.isNotEmpty() }
        }.toSet()
        Meta(upMid, tags, null)
    }.getOrNull()

    fun extractTagsFromViewUniteReply(reply: Any): Set<String> {
        val tags = linkedSetOf<String>()
        runCatching {
            val tab = reply.callMethodOrNull("getTab") ?: return@runCatching
            @Suppress("UNCHECKED_CAST")
            val tabModules = tab.callMethodOrNull("getTabModuleList") as? List<*> ?: return@runCatching
            for (tabModule in tabModules) {
                if (tabModule?.callMethodOrNull("hasIntroduction") != true) continue
                val introTab = tabModule.callMethodOrNull("getIntroduction") ?: continue
                @Suppress("UNCHECKED_CAST")
                val modules = introTab.callMethodOrNull("getModulesList") as? List<*> ?: continue
                for (module in modules) {
                    if (module?.callMethodOrNull("hasUgcIntroduction") != true) continue
                    val ugcIntro = module.callMethodOrNull("getUgcIntroduction") ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val tagList = ugcIntro.callMethodOrNull("getTagsList") as? List<*> ?: continue
                    for (tag in tagList) {
                        val name = tag?.callMethodOrNullAs<String>("getName")?.trim().orEmpty()
                        if (name.isNotEmpty()) tags.add(name)
                    }
                }
            }
        }
        return tags
    }

    fun parseUpMidFromRelateCard(card: Any): Long? = runCatching {
        val basic = card.callMethodOrNull("getBasicInfo") ?: return@runCatching null
        if (basic.callMethodOrNull("hasAuthor") != true) return@runCatching null
        basic.callMethodOrNull("getAuthor")?.callMethodOrNullAs<Long>("getMid")?.takeIf { it > 0L }
    }.getOrNull()

    fun isPortraitDimension(dimension: Any): Boolean {
        val rawW = (dimension.javaClass.getMethod("getWidth").invoke(dimension) as Number).toLong()
        val rawH = (dimension.javaClass.getMethod("getHeight").invoke(dimension) as Number).toLong()
        val rotate = (dimension.javaClass.getMethod("getRotate").invoke(dimension) as Number).toLong()
        val width = if (rotate == 1L) rawH else rawW
        val height = if (rotate == 1L) rawW else rawH
        return height > width
    }
}
