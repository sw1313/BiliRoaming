package me.custom.biliextras

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import me.custom.biliextras.hook.*
import me.custom.biliextras.utils.*

class XposedInit : XposedModule() {
    private lateinit var processName: String

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        modulePath = getModuleApplicationInfo().sourceDir
        moduleRes = getModuleRes(modulePath)
        instance = this
        processName = param.processName
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        val classLoader = param.classLoader
        if (BuildConfig.APPLICATION_ID == packageName) {
            MainActivity::class.java.name.hookMethod(classLoader, "isModuleActive") { true }
            return
        }
        if (packageName !in Constant.BILIBILI_PACKAGE_NAMES &&
            "tv.danmaku.bili.MainActivityV2".findClassOrNull(classLoader) == null
        ) return

        Instrumentation::class.java.hookMethod("callApplicationOnCreate", Application::class.java) { chain ->
            if (!processName.contains(":")) {
                val context = chain.args[0] as android.content.Context
                if (context.packageName != packageName) return@hookMethod chain.proceed()
                initHostContext(context)
                Log.d("BiliExtras activated in $packageName")
                Log.d("Bilibili version: ${getPackageVersion(packageName)}")
                Log.d("Config: ${ePrefs.all}")
                Log.toast("漫游扩展已激活")
                BiliPackageLite(classLoader, context)
                startHook { ExtrasSettingHook(classLoader) }
                startHook { BlockUpShareGoodsHook(classLoader) }
                startHook { BlockStoryLiveHook(classLoader) }
                startHook { BlockStoryGoodsHook(classLoader) }
                startHook { StoryBackgroundAutoNextHook(classLoader) }
                startHook { ForegroundAutoNextHook(classLoader) }
                startHook { MediaButtonControlHook(classLoader) }
                startHook { HideVipCenterHook(classLoader) }
                startHook { DisableChapterProgressHook(classLoader) }
                startHook { SponsorBlockHook(classLoader) }
                startHook { SponsorBlockProgressHook(classLoader) }
            }
            chain.proceed()
        }
    }

    private fun startHook(hookerCreator: () -> BaseHook) {
        try {
            hookerCreator().startHook()
        } catch (e: Throwable) {
            Log.e(e)
            Log.toast("漫游扩展出错：${e.message}", force = true)
        }
    }

    companion object {
        lateinit var modulePath: String
        lateinit var moduleRes: Resources
        lateinit var instance: XposedInit

        @JvmStatic
        @Suppress("DiscouragedPrivateApi")
        fun getModuleRes(path: String): Resources {
            val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
            AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
                .invoke(assetManager, path)
            return Resources(assetManager, null, null)
        }
    }
}
