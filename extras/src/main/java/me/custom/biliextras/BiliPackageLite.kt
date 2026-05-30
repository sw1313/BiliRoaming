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
    val fastjsonFieldAnnotation by lazy { "com.alibaba.fastjson.annotation.JSONField".from(classLoader) }
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

    val playerCoreMethods by lazy { findPlayerCoreMethods() }
    val pegasusConvertClass by lazy { findPegasusConvertClass() }

    private fun findPegasusConvertClass(): Class<*>? {
        return withDexHelper { dexHelper ->
            dexHelper.findMethodUsingString(
                "card_type is empty",
                false,
                -1,
                -1,
                null,
                -1,
                null,
                null,
                null,
                true,
            ).asSequence().firstNotNullOfOrNull {
                dexHelper.decodeMethodIndex(it)
            }?.declaringClass
        }.onSuccess {
            Log.d("Pegasus convert class dex result: ${it?.name}")
        }.onFailure {
            Log.e(it)
        }.getOrNull()
    }

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

    private fun <T> withDexHelper(block: (DexHelper) -> T): Result<T> {
        return runCatching {
            // 启动批量挂钩期间复用同一个 DexHelper，避免每个 lazy 查找各自重新解析整个 dex。
            sharedDexHelper?.let { return@runCatching block(it) }
            loadDexHelperLibrary()
            val realClassLoader = classLoader.findDexClassLoader(::findRealClassloader)
                ?: error("No BaseDexClassLoader found")
            DexHelper(realClassLoader).use { dexHelper ->
                block(dexHelper)
            }
        }
    }

    /**
     * 在一次会话内只构造一个 DexHelper 并共享给所有 lazy 查找（启动时一次性挂钩用）。
     * 仅是复用同一份已解析的 dex，查找逻辑与结果完全不变；构造失败时回退为各自新建。
     */
    fun runWithSharedDex(block: () -> Unit) {
        if (sharedDexHelper != null) {
            block()
            return
        }
        val helper = runCatching {
            loadDexHelperLibrary()
            val realClassLoader = classLoader.findDexClassLoader(::findRealClassloader)
                ?: error("No BaseDexClassLoader found")
            DexHelper(realClassLoader)
        }.getOrElse {
            Log.e(it)
            null
        }
        if (helper == null) {
            block()
            return
        }
        try {
            sharedDexHelper = helper
            block()
        } finally {
            sharedDexHelper = null
            runCatching { helper.close() }
        }
    }

    fun homeUserCenterFallback() = homeUserCenterClass

    companion object {
        @Volatile
        lateinit var instance: BiliPackageLite
        @Volatile
        private var dexHelperLibraryLoaded = false
        @Volatile
        private var sharedDexHelper: DexHelper? = null

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
