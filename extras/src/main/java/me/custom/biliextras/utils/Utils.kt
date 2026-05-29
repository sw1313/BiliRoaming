@file:Suppress("DEPRECATION")

package me.custom.biliextras.utils

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import me.custom.biliextras.Constant
import me.custom.biliextras.XposedInit
import android.util.Log as ALog

private lateinit var hostContext: Context

object Log {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var toast: Toast? = null

    fun toast(msg: String, force: Boolean = false, duration: Int = Toast.LENGTH_SHORT) {
        if (!force && !ePrefs.getBoolean("show_info", true)) return
        handler.post {
            toast?.cancel()
            toast = Toast.makeText(currentContext, "漫游扩展：$msg", duration).apply { show() }
        }
        w(msg)
    }

    private fun doLog(f: (String, String) -> Int, obj: Any?, toXposed: Boolean = false) {
        val str = if (obj is Throwable) ALog.getStackTraceString(obj) else obj.toString()
        f(Constant.TAG, str)
        if (toXposed) {
            XposedInit.instance.log(ALog.ERROR, Constant.TAG, str)
        }
    }

    fun d(obj: Any?) = doLog(ALog::d, obj)
    fun i(obj: Any?) = doLog(ALog::i, obj)
    fun e(obj: Any?) = doLog(ALog::e, obj, true)
    fun w(obj: Any?) = doLog(ALog::w, obj)
    fun x(obj: Any?) = doLog(ALog::i, obj, true)
}

fun initHostContext(context: Context) {
    hostContext = context.applicationContext ?: context
}

val currentContext: Context
    get() = if (::hostContext.isInitialized) {
        hostContext
    } else {
        val activityThread = "android.app.ActivityThread".findClassOrNull(null)
            ?.callStaticMethod("currentActivityThread")!!
        activityThread.callMethodAs("getSystemContext")
    }

val hasHostContext: Boolean
    get() = ::hostContext.isInitialized

val ePrefs: SharedPreferences
    get() {
        check(::hostContext.isInitialized) { "Host context is not initialized" }
        return hostContext.getSharedPreferences(Constant.PREFS_NAME, Context.MODE_MULTI_PROCESS)
    }

fun getPackageVersion(packageName: String) = try {
    @Suppress("DEPRECATION")
    currentContext.packageManager.getPackageInfo(packageName, 0).run {
        "${packageName}@${versionName}(${longVersionCode})"
    }
} catch (_: Throwable) {
    "(unknown)"
}

fun Context.addModuleAssets() {
    resources.assets.callMethod("addAssetPath", XposedInit.modulePath)
}
