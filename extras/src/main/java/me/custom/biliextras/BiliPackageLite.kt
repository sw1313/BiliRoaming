package me.custom.biliextras

import android.content.Context
import dalvik.system.BaseDexClassLoader
import me.custom.biliextras.utils.*
import me.iacn.biliroaming.utils.DexHelper
import java.lang.reflect.Method

class BiliPackageLite(
    private val classLoader: ClassLoader,
    context: Context,
) {
    val fastJsonClass by lazy { "com.alibaba.fastjson.JSON".from(classLoader) }
    val generalResponseClass by lazy {
        "com.bilibili.okretro.GeneralResponse".from(classLoader)
            ?: "com.bilibili.okretro.call.GeneralResponse".from(classLoader)
    }
    val menuGroupItemClass by lazy { "com.bilibili.lib.homepage.mine.MenuGroup\$Item".from(classLoader) }
    val mainActivityClass by lazy { "tv.danmaku.bili.MainActivityV2".from(classLoader) }
    val splashActivityClass by lazy {
        "tv.danmaku.bili.ui.splash.SplashActivity".from(classLoader) ?: mainActivityClass
    }
    val homeUserCenterClass by lazy { findHomeUserCenterClass(classLoader) }
    val settingRouterClass by lazy { findSettingRouterClass() }
    val viewMossClass by lazy { "com.bapis.bilibili.app.view.v1.ViewMoss".from(classLoader) }
    val viewReqClass by lazy { "com.bapis.bilibili.app.view.v1.ViewReq".from(classLoader) }
    val viewUniteMossClass by lazy { "com.bapis.bilibili.app.viewunite.v1.ViewMoss".from(classLoader) }
    val viewUniteReqClass by lazy { "com.bapis.bilibili.app.viewunite.v1.ViewReq".from(classLoader) }
    val playerMossClass by lazy { "com.bapis.bilibili.app.playerunite.v1.PlayerMoss".from(classLoader) }
    val playViewUniteReqClass by lazy { "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq".from(classLoader) }
    val mossResponseHandlerClass by lazy { "com.bilibili.lib.moss.api.MossResponseHandler".from(classLoader) }
    val storyPagerPlayerClass by lazy { "com.bilibili.video.story.player.StoryPagerPlayer".from(classLoader) }
    val useNewMossFunc by lazy { viewMossClass?.declaredMethods?.any { it.name == "executeView" } == true }
    val clientVersionCode = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

    init {
        instance = this
        Log.d("BiliExtras loaded for ${getPackageVersion(context.packageName)}")
    }

    fun fastJsonParse() = "parseObject"

    private val cachedAddVideoMethod by lazy {
        val clazz = storyPagerPlayerClass
        if (clazz == null) {
            null
        } else {
            withDexHelper { dexHelper ->
                dexHelper.findMethodUsingString(
                    " add ",
                    false,
                    -1,
                    -1,
                    null,
                    dexHelper.encodeClassIndex(clazz),
                    null,
                    longArrayOf(dexHelper.encodeClassIndex(List::class.java)),
                    null,
                    true,
                ).asSequence().firstNotNullOfOrNull {
                    dexHelper.decodeMethodIndex(it) as? Method
                }
            }.onSuccess {
                Log.d("StoryPagerPlayer addVideo dex result: ${it?.name}")
            }.onFailure {
                Log.e(it)
            }.getOrNull()
        }
    }

    fun addVideoMethod(): Method? = cachedAddVideoMethod

    data class PlayerCoreMethods(
        val serviceClass: Class<*>,
        val seekTo: Method,
        val currentPosition: Method?,
    )

    data class StoryAutoNextMethods(
        val completionMethods: List<Method>,
        val nextMethods: List<Method>,
    )

    val playerCoreMethods by lazy { findPlayerCoreMethods() }
    val chapterProgressSwitchMethods by lazy { findChapterProgressSwitchMethods() }
    val storyAutoNextMethods by lazy { findStoryAutoNextMethods() }

    fun homeCenters(): List<Pair<Class<*>?, List<Method>>> {
        return withDexHelper { dexHelper ->
            val contextIndex = dexHelper.encodeClassIndex(Context::class.java)
            val listIndex = dexHelper.encodeClassIndex(List::class.java)
            dexHelper.findMethodUsingString(
                "main.my-information.noportrait.0.show",
                false,
                -1,
                -1,
                null,
                -1,
                null,
                null,
                null,
                false,
            ).asSequence().mapNotNull { dexHelper.decodeMethodIndex(it)?.declaringClass }
                .mapNotNull { homeUserCenterClass ->
                    val homeUserCenterIndex = dexHelper.encodeClassIndex(homeUserCenterClass)
                    val addSettingMethod = dexHelper.findMethodUsingString(
                        "bilibili://main/scan",
                        true,
                        -1,
                        -1,
                        null,
                        homeUserCenterIndex,
                        null,
                        longArrayOf(contextIndex),
                        null,
                        false,
                    ).asSequence().mapNotNull {
                        dexHelper.decodeMethodIndex(it) as? Method
                    }.firstOrNull {
                        it.parameterTypes.size == 2 && it.parameterTypes[1] != List::class.java
                    } ?: dexHelper.findMethodUsingString(
                        "activity://main/preference",
                        true,
                        -1,
                        -1,
                        null,
                        homeUserCenterIndex,
                        null,
                        longArrayOf(contextIndex, listIndex),
                        null,
                        true,
                    ).asSequence().firstNotNullOfOrNull {
                        dexHelper.decodeMethodIndex(it) as? Method
                    } ?: dexHelper.findMethodUsingString(
                        "bilibili://main/preference",
                        true,
                        -1,
                        -1,
                        null,
                        homeUserCenterIndex,
                        null,
                        longArrayOf(contextIndex, listIndex),
                        null,
                        true,
                    ).asSequence().firstNotNullOfOrNull {
                        dexHelper.decodeMethodIndex(it) as? Method
                    } ?: return@mapNotNull null
                    homeUserCenterClass to listOf(addSettingMethod)
                }.toList()
        }.onSuccess {
            Log.d(
                "Home center dex result: ${
                    it.joinToString { (clazz, methods) ->
                        "${clazz?.name}#${methods.joinToString { method -> method.name }}"
                    }
                }"
            )
        }.onFailure {
            Log.e(it)
        }.getOrDefault(emptyList())
    }

    private fun findSettingRouterClass(): Class<*>? {
        return withDexHelper { dexHelper ->
            dexHelper.findMethodUsingString(
                "UperHotMineSolution",
                false,
                -1,
                0,
                "V",
                -1,
                null,
                null,
                null,
                true,
            ).asSequence().firstNotNullOfOrNull {
                dexHelper.decodeMethodIndex(it)
            }?.declaringClass?.interfaces?.firstOrNull()?.let {
                dexHelper.encodeClassIndex(it)
            }?.let {
                dexHelper.findField(it, null, true).asSequence().firstNotNullOfOrNull { field ->
                    dexHelper.decodeFieldIndex(field)
                }?.declaringClass
            }
        }.onSuccess {
            Log.d("Setting router dex result: ${it?.name}")
        }.onFailure {
            Log.e(it)
        }.getOrNull()
    }

    private fun findPlayerCoreMethods(): PlayerCoreMethods? {
        return withDexHelper { dexHelper ->
            val seekToMethod = dexHelper.findMethodUsingString(
                "[player]seek to",
                true,
                -1,
                1,
                "VI",
                -1,
                null,
                null,
                null,
                true,
            ).asSequence().firstNotNullOfOrNull {
                dexHelper.decodeMethodIndex(it) as? Method
            } ?: run {
                val doSeekToIndex = dexHelper.findMethodUsingString(
                    "[player]seek to",
                    true,
                    -1,
                    2,
                    "VIZ",
                    -1,
                    null,
                    null,
                    null,
                    true,
                ).firstOrNull() ?: return@withDexHelper null
                val doSeekToMethod = dexHelper.decodeMethodIndex(doSeekToIndex) as? Method
                    ?: return@withDexHelper null
                val playerCoreServiceIndex = dexHelper.encodeClassIndex(doSeekToMethod.declaringClass)
                dexHelper.findMethodInvoked(
                    doSeekToIndex,
                    -1,
                    1,
                    "VI",
                    playerCoreServiceIndex,
                    null,
                    null,
                    null,
                    true,
                ).asSequence().firstNotNullOfOrNull {
                    dexHelper.decodeMethodIndex(it) as? Method
                } ?: dexHelper.findMethodInvoked(
                    doSeekToIndex,
                    -1,
                    2,
                    "VIZ",
                    playerCoreServiceIndex,
                    null,
                    null,
                    null,
                    true,
                ).asSequence().firstNotNullOfOrNull {
                    dexHelper.decodeMethodIndex(it) as? Method
                } ?: doSeekToMethod
            }
            seekToMethod.isAccessible = true
            val playerCoreServiceClass = seekToMethod.declaringClass
            val currentPositionMethod = playerCoreServiceClass.declaredMethods.firstOrNull {
                it.parameterCount == 0 &&
                    (it.returnType == Int::class.javaPrimitiveType || it.returnType == Long::class.javaPrimitiveType) &&
                    (it.name == "getCurrentPosition" || it.name == "getRealCurrentPosition")
            } ?: playerCoreServiceClass.declaredMethods.firstOrNull {
                it.parameterCount == 0 &&
                    (it.returnType == Int::class.javaPrimitiveType || it.returnType == Long::class.javaPrimitiveType) &&
                    it.name.contains("position", ignoreCase = true)
            }
            currentPositionMethod?.isAccessible = true
            PlayerCoreMethods(playerCoreServiceClass, seekToMethod, currentPositionMethod)
        }.onSuccess {
            Log.d(
                "Player core dex result: ${
                    it?.serviceClass?.name
                }#seek=${it?.seekTo?.name}, pos=${it?.currentPosition?.name}"
            )
        }.onFailure {
            Log.e(it)
        }.getOrNull()
    }

    private fun findChapterProgressSwitchMethods(): List<Method> {
        return withDexHelper { dexHelper ->
            val markers = listOf(
                "main.ugc-video-detail.resident-chapter.control-switch.click",
                "player.player.option-chapter.0.player",
                "main.ugc-video-detail.chapter.0.click",
            )
            markers.flatMap { marker ->
                dexHelper.findMethodUsingString(
                    marker,
                    true,
                    -1,
                    -1,
                    null,
                    -1,
                    null,
                    null,
                    null,
                    false,
                ).asSequence().mapNotNull {
                    dexHelper.decodeMethodIndex(it) as? Method
                }.toList()
            }.distinctBy { "${it.declaringClass.name}#${it.name}${it.parameterTypes.joinToString(prefix = "(", postfix = ")") { type -> type.name }}" }
                .onEach { it.isAccessible = true }
        }.onSuccess {
            Log.x(
                "Chapter progress dex result: ${
                    it.joinToString { method ->
                        "${method.declaringClass.name}#${method.name}/${method.parameterCount}:${method.returnType.name}"
                    }
                }",
            )
        }.onFailure {
            Log.e(it)
        }.getOrDefault(emptyList())
    }

    private fun findStoryAutoNextMethods(): StoryAutoNextMethods {
        val storyClass = storyPagerPlayerClass ?: return StoryAutoNextMethods(emptyList(), emptyList())
        return withDexHelper { dexHelper ->
            val storyClassIndex = dexHelper.encodeClassIndex(storyClass)
            val markers = listOf(
                "next",
                "Next",
                "auto",
                "Auto",
                "complete",
                "Complete",
                "finish",
                "Finish",
                "scroll",
                "Scroll",
                "slide",
                "Slide",
            )
            val markerMethods = markers.flatMap { marker ->
                dexHelper.findMethodUsingString(
                    marker,
                    false,
                    -1,
                    -1,
                    null,
                    storyClassIndex,
                    null,
                    null,
                    null,
                    false,
                ).asSequence().mapNotNull { dexHelper.decodeMethodIndex(it) as? Method }.toList()
            }
            val declared = storyClass.declaredMethods.asSequence().filter { method ->
                method.parameterCount <= 2 &&
                    (method.returnType == Void.TYPE || method.returnType == Boolean::class.javaPrimitiveType)
            }
            val candidates = (markerMethods.asSequence() + declared)
                .distinctBy { method ->
                    "${method.declaringClass.name}#${method.name}${method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }}"
                }
                .onEach { it.isAccessible = true }
                .toList()
            val nextMethods = candidates.filter(::isStoryNextCandidate).take(12)
            val completionMethods = candidates.filter(::isStoryCompletionCandidate).take(12)
            StoryAutoNextMethods(completionMethods, nextMethods)
        }.onSuccess { methods ->
            Log.x(
                "StoryAutoNext dex result: completion=${methods.completionMethods.joinToString { it.shortSignature() }}, " +
                    "next=${methods.nextMethods.joinToString { it.shortSignature() }}",
            )
        }.onFailure {
            Log.e(it)
        }.getOrDefault(StoryAutoNextMethods(emptyList(), emptyList()))
    }

    private fun isStoryNextCandidate(method: Method): Boolean {
        val name = method.name.lowercase()
        return method.parameterCount <= 1 &&
            (method.returnType == Void.TYPE || method.returnType == Boolean::class.javaPrimitiveType) &&
            (name.contains("next") ||
                name.contains("scroll") ||
                name.contains("slide") ||
                name.contains("turn") ||
                name.contains("page"))
    }

    private fun isStoryCompletionCandidate(method: Method): Boolean {
        val name = method.name.lowercase()
        return method.parameterCount <= 2 &&
            (name.contains("complete") ||
                name.contains("completion") ||
                name.contains("finish") ||
                name.contains("ended") ||
                name.contains("playend"))
    }

    private fun Method.shortSignature(): String =
        "${declaringClass.name}#$name/${parameterCount}:${returnType.simpleName}"

    private fun <T> withDexHelper(block: (DexHelper) -> T): Result<T> {
        return runCatching {
            loadDexHelperLibrary()
            val realClassLoader = classLoader.findDexClassLoader(::findRealClassloader)
                ?: error("No BaseDexClassLoader found")
            DexHelper(realClassLoader).use { dexHelper ->
                block(dexHelper)
            }
        }
    }

    fun homeUserCenterFallback() = homeUserCenterClass

    companion object {
        @Volatile
        lateinit var instance: BiliPackageLite
        @Volatile
        private var dexHelperLibraryLoaded = false

        private fun loadDexHelperLibrary() {
            if (dexHelperLibraryLoaded) return
            synchronized(BiliPackageLite::class.java) {
                if (!dexHelperLibraryLoaded) {
                    System.loadLibrary("biliroaming")
                    dexHelperLibraryLoaded = true
                }
            }
        }

        fun findRealClassloader(classloader: BaseDexClassLoader): BaseDexClassLoader {
            val serviceField = classloader.javaClass.findFirstFieldByExactTypeOrNull(
                "com.bilibili.lib.tribe.core.internal.loader.DefaultClassLoaderService" from classloader,
            )
            val delegateField = classloader.javaClass.findFirstFieldByExactTypeOrNull(
                "com.bilibili.lib.tribe.core.internal.loader.TribeLoaderDelegate" from classloader,
            )
            return if (serviceField != null) {
                serviceField.type.declaredFields.filter { f ->
                    f.type == ClassLoader::class.java
                }.map { f ->
                    classloader.getObjectFieldOrNull(serviceField.name)
                        ?.getObjectFieldOrNull(f.name)
                }.firstOrNull { o ->
                    o?.javaClass?.name?.startsWith("com.bilibili") == false
                } as? BaseDexClassLoader ?: classloader
            } else if (delegateField != null) {
                val loaderField = delegateField.type.findFirstFieldByExactTypeOrNull(ClassLoader::class.java)
                val out = classloader.getObjectFieldOrNull(delegateField.name)
                    ?.getObjectFieldOrNull(loaderField?.name)
                if (BaseDexClassLoader::class.java.isInstance(out)) out as BaseDexClassLoader else classloader
            } else {
                classloader
            }
        }

        private fun findHomeUserCenterClass(classLoader: ClassLoader): Class<*>? {
            sequenceOf(
                "com.bilibili.bilibili.home.usermine.HomeUserCenterFragment",
                "com.bilibili.lib.homepage.mine.HomeUserCenterFragment",
            ).mapNotNull { it.from(classLoader) }.firstOrNull()?.let { return it }

            return classLoader.allClassesList()
                .asSequence()
                .filter { it.contains("HomeUserCenter", ignoreCase = true) || it.contains("MineFragment", ignoreCase = true) }
                .mapNotNull { it.from(classLoader) }
                .firstOrNull { clazz ->
                    clazz.declaredFields.any { it.type.name.contains("MineVipModuleManager") }
                }
        }
    }
}
