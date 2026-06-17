@file:Suppress("DEPRECATION")

package me.custom.biliextras.utils

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.Constant
import me.custom.biliextras.XposedInit
import android.util.Log as ALog
import java.lang.reflect.Proxy

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

    const val KEY_VERBOSE = "extras_verbose_log"

    /**
     * Verbose tracing is opt-in: only errors ([e]) and one-shot startup confirmations ([s]) are
     * emitted by default. All other channels ([d]/[i]/[w]/[x]) are per-event traces and stay silent
     * unless the "详细日志" toggle is on, so the LSPosed log only keeps the necessary lines.
     */
    @Volatile
    private var cachedVerbose: Boolean? = null

    private val verbose: Boolean
        get() = cachedVerbose ?: runCatching { ePrefs.getBoolean(KEY_VERBOSE, false) }
            .getOrDefault(false).also { cachedVerbose = it }

    fun refreshVerboseCache() {
        cachedVerbose = runCatching { ePrefs.getBoolean(KEY_VERBOSE, false) }.getOrDefault(false)
    }

    private fun doLog(f: (String, String) -> Int, obj: Any?, toXposed: Boolean = false) {
        val str = if (obj is Throwable) ALog.getStackTraceString(obj) else obj.toString()
        f(Constant.TAG, str)
        if (toXposed) {
            XposedInit.instance.log(ALog.ERROR, Constant.TAG, str)
        }
    }

    fun d(obj: Any?) { if (verbose) doLog(ALog::d, obj) }
    fun i(obj: Any?) { if (verbose) doLog(ALog::i, obj) }
    fun e(obj: Any?) = doLog(ALog::e, obj, true)
    fun w(obj: Any?) { if (verbose) doLog(ALog::w, obj) }
    fun x(obj: Any?) { if (verbose) doLog(ALog::i, obj, true) }

    /** Lazy verbose traces — string building runs only when详细日志 is on. */
    fun d(lazyMessage: () -> Any?) {
        if (verbose) doLog(ALog::d, lazyMessage(), false)
    }

    fun i(lazyMessage: () -> Any?) {
        if (verbose) doLog(ALog::i, lazyMessage(), false)
    }

    fun w(lazyMessage: () -> Any?) {
        if (verbose) doLog(ALog::w, lazyMessage(), false)
    }

    fun trace(lazyMessage: () -> Any?) {
        if (verbose) doLog(ALog::i, lazyMessage(), true)
    }

    /** One-shot startup/feature-activation confirmations: always emitted (necessary log). */
    fun s(obj: Any?) = doLog(ALog::i, obj, true)
}

fun initHostContext(context: Context) {
    hostContext = context.applicationContext ?: context
    Log.refreshVerboseCache()
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

/**
 * Wrap a [com.bilibili.lib.moss.api.MossResponseHandler] so [onNext] runs before the original
 * handler's onNext. Return a non-null reply from [onNext] to replace the payload; return null
 * to leave it unchanged.
 */
fun Any.mossResponseHandlerReplaceProxy(onNext: (reply: Any?) -> Any?): Any {
    val originalHandler = this
    val handlerClass = instance.mossResponseHandlerClass
        ?: throw IllegalStateException("MossResponseHandler class not found")
    return Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(handlerClass),
    ) { _, method, args ->
        when (method.name) {
            "onNext" -> {
                onNext(args[0])?.let { args[0] = it }
                method.invoke(originalHandler, *(args ?: emptyArray()))
            }
            "onError" -> {
                val newResponse = onNext(null)
                if (newResponse == null) {
                    method.invoke(originalHandler, *(args ?: emptyArray()))
                } else {
                    originalHandler.callMethod("onNext", newResponse)
                    originalHandler.callMethod("onCompleted")
                }
            }
            else -> if (args == null) method.invoke(originalHandler) else method.invoke(originalHandler, *args)
        }
    }
}
