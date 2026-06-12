package me.custom.biliextras.sponsorblock

import android.app.Activity
import me.custom.biliextras.utils.callMethod
import me.custom.biliextras.utils.callMethodOrNull
import me.custom.biliextras.utils.from
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

object SponsorBlockVideoSettingDialog {
    private var activeDialogRef: WeakReference<Any>? = null
    private val reflectionByLoader = ConcurrentHashMap<ClassLoader, DialogReflection>()

    fun show(activity: Activity, rows: List<Any>) {
        dismiss()
        val reflection = reflectionByLoader.getOrPut(activity.classLoader) {
            DialogReflection(activity.classLoader)
        }
        val dialog = reflection.createDialog(
            activity,
            rows,
            SponsorBlockMenuHost.isFullscreenWidget,
        )
        activeDialogRef = WeakReference(dialog)
        dialog.callMethod("show")
    }

    fun dismiss() {
        activeDialogRef?.get()?.callMethodOrNull("dismiss")
        activeDialogRef = null
    }

    private class DialogReflection(private val classLoader: ClassLoader) {
        private val dialogClass by lazy {
            "com.bilibili.playerbizcommonv2.widget.setting.channel.VideoSettingDialog".from(classLoader)
                ?: error("VideoSettingDialog missing")
        }
        private val dialogConstructor by lazy { dialogClass.dialogConstructor() }
        private val styleType by lazy { dialogConstructor.parameterTypes[2] }
        private val topStyle by lazy { resolveTopStyle(styleType) }
        private val heightRatioConstructors by lazy {
            styleType.declaredClasses.mapNotNull { nested ->
                nested.declaredConstructors.firstOrNull { c ->
                    c.parameterCount == 1 && c.parameterTypes[0] == Float::class.javaPrimitiveType
                }?.apply { isAccessible = true }
            }
        }

        fun createDialog(activity: Activity, rows: List<Any>, landscapeFullscreen: Boolean): Any {
            val style = resolveVideoSettingStyle(landscapeFullscreen)
                ?: error("VideoSetting style not resolved")
            return dialogConstructor.newInstance(activity, ArrayList(rows), style, 0.0f, 0)
        }

        private fun resolveVideoSettingStyle(landscapeFullscreen: Boolean): Any? {
            if (landscapeFullscreen) {
                resolveHeightRatioStyle(0.55f)?.let { return it }
            }
            topStyle?.let { return it }
            return resolveHeightRatioStyle(0.5f)
        }

        private fun resolveTopStyle(styleInterface: Class<*>): Any? {
            for (nested in styleInterface.declaredClasses) {
                for (field in nested.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) && field.type == nested) {
                        field.isAccessible = true
                        field.get(null)?.let { return it }
                    }
                }
            }
            return null
        }

        private fun resolveHeightRatioStyle(ratio: Float): Any? {
            for (ctor in heightRatioConstructors) {
                return ctor.newInstance(ratio)
            }
            return null
        }

        private fun Class<*>.dialogConstructor(): Constructor<*> {
            val marker = "kotlin.jvm.internal.DefaultConstructorMarker"
            return declaredConstructors.firstOrNull { ctor ->
                ctor.parameterCount == 5 && ctor.parameterTypes.none { it.name == marker }
            } ?: declaredConstructors.first { it.parameterCount == 5 }
        }
    }
}
