package me.custom.biliextras.sponsorblock

import me.custom.biliextras.playback.PlayerMenuKind
import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.callMethodOrNull
import java.lang.ref.WeakReference

/** Remembers the fullscreen player settings widget so submenu can hide it like native rows. */
object SponsorBlockMenuHost {
    private var widgetRef: WeakReference<Any>? = null
    @Volatile
    var isFullscreenWidget: Boolean = false
        private set
    @Volatile
    var playerMenuKind: PlayerMenuKind = PlayerMenuKind.UGC
        private set

    fun setPlayerMenuKind(kind: PlayerMenuKind) {
        playerMenuKind = kind
    }

    fun setFullscreenWidget(widget: Any) {
        widgetRef = WeakReference(widget)
        isFullscreenWidget = true
    }

    fun clearFullscreenWidget() {
        isFullscreenWidget = false
        widgetRef = null
    }

    fun hidePlayerSettingWidgetIfNeeded() {
        if (!isFullscreenWidget) return
        val widget = widgetRef?.get() ?: return
        runCatching {
            val service = widget.javaClass.declaredFields
                .firstOrNull { it.type.name.contains("AbsFunctionWidgetService") }
                ?.apply { isAccessible = true }
                ?.get(widget)
                ?: return
            val token = widget.callMethodOrNull("getToken") ?: return
            service.callMethodOrNull("hideWidget", token)
        }.onFailure {
            Log.w { "SponsorBlock: hide player setting widget failed: ${it.message}" }
        }
    }
}
