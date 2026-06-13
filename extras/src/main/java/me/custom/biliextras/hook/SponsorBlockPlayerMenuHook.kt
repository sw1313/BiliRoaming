package me.custom.biliextras.hook

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import me.custom.biliextras.playback.PlaybackMenuRows
import me.custom.biliextras.playback.PlayerMenuKind
import me.custom.biliextras.sponsorblock.SponsorBlockController
import me.custom.biliextras.sponsorblock.SponsorBlockMenuHost
import me.custom.biliextras.sponsorblock.SponsorBlockMenuUiFactory
import me.custom.biliextras.sponsorblock.SponsorBlockPrefs
import me.custom.biliextras.sponsorblock.SponsorBlockSubMenu
import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.callMethodOrNull
import me.custom.biliextras.utils.from
import me.custom.biliextras.utils.hookMethod
import java.util.concurrent.atomic.AtomicBoolean

class SponsorBlockPlayerMenuHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val uiFactory by lazy { SponsorBlockMenuUiFactory.forClassLoader(classLoader) }
    private val settingListLocal = ThreadLocal<MutableList<Any>>()
    private val fabShown = AtomicBoolean(false)

    private companion object {
        const val TYPE_AUTO_PLAY = "SETTING_PLAYBACK_MODE"
        const val TYPE_STORY_AUTO_SCROLL = "SETTING_AUTOMATIC_SCROLL"
    }

    override fun startHook() {
        hookFullscreenPlayerMenu()
        hookTheseusHalfScreenMenu()
        hookStoryMenu()
        hookFabFallback()
        Log.x("SponsorBlockPlayerMenu: hooks installed, native=${uiFactory.isNativeAvailable()}")
    }

    private fun hookFullscreenPlayerMenu() {
        val widgetClass = "com.bilibili.playerbizcommonv2.widget.setting.PlayerSettingFunctionWidget2"
            .from(mClassLoader) ?: return
        val videoSettingTypeClass = "com.bilibili.playerbizcommonv2.widget.setting.channel.VideoSettingType"
            .from(mClassLoader) ?: return
        widgetClass.hookMethod("v0", Int::class.javaPrimitiveType!!, videoSettingTypeClass) { chain ->
            @Suppress("UNCHECKED_CAST")
            val original = chain.proceed() as List<Any>
            if (chain.args[0] as Int != 2) return@hookMethod original
            SponsorBlockMenuHost.setFullscreenWidget(chain.thisObject)
            val context = extractContext(chain.thisObject) ?: return@hookMethod original
            val settingType = chain.args[1]
            prependMainEntry(original.toMutableList(), context, settingType, PlayerMenuKind.UGC)
        }
        Log.x("SponsorBlockPlayerMenu: hooked PlayerSettingFunctionWidget2.v0(autoPlayer)")
    }

    private fun hookTheseusHalfScreenMenu() {
        val menuClass = "com.bilibili.ship.theseus.united.page.toolbar.MenuService".from(mClassLoader) ?: return
        val settingItemClass = "com.bapis.bilibili.playershared.SettingItem".from(mClassLoader) ?: return
        val videoSettingTypeClass = "com.bilibili.playerbizcommonv2.widget.setting.channel.VideoSettingType"
            .from(mClassLoader) ?: return
        hookSettingListScope(
            "com.bilibili.ship.theseus.united.page.toolbar.MenuService\$createSettingGroup\$2",
        )
        menuClass.hookMethod("g1", settingItemClass, videoSettingTypeClass) { chain ->
            val settingItem = chain.args[0]
            if (settingItemTypeName(settingItem) == TYPE_AUTO_PLAY) {
                SponsorBlockMenuHost.clearFullscreenWidget()
                injectMainEntry(chain.thisObject, chain.args[1], PlayerMenuKind.UGC)
            }
            chain.proceed()
        }
        Log.x("SponsorBlockPlayerMenu: hooked Theseus MenuService.g1")
    }

    private fun hookStoryMenu() {
        val storyMenuClass = "com.bilibili.video.story.setting.StoryMenuService".from(mClassLoader) ?: return
        val settingItemClass = "com.bapis.bilibili.playershared.SettingItem".from(mClassLoader) ?: return
        val videoSettingTypeClass = "com.bilibili.playerbizcommonv2.widget.setting.channel.VideoSettingType"
            .from(mClassLoader) ?: return
        val cardInfoClass = "com.bilibili.video.story.setting.a".from(mClassLoader) ?: return
        hookSettingListScope(
            "com.bilibili.video.story.setting.StoryMenuService\$createSettingGroup\$2",
        )
        storyMenuClass.hookMethod(
            "m0",
            settingItemClass,
            videoSettingTypeClass,
            cardInfoClass,
        ) { chain ->
            val settingItem = chain.args[0]
            if (settingItemTypeName(settingItem) == TYPE_STORY_AUTO_SCROLL) {
                SponsorBlockMenuHost.clearFullscreenWidget()
                injectMainEntry(chain.thisObject, chain.args[1], PlayerMenuKind.STORY)
            }
            chain.proceed()
        }
        Log.x("SponsorBlockPlayerMenu: hooked StoryMenuService.m0(autoScroll)")
    }

    private fun hookSettingListScope(createGroupClassName: String) {
        val createGroupClass = createGroupClassName.from(mClassLoader) ?: run {
            Log.w("SponsorBlockPlayerMenu: missing $createGroupClassName")
            return
        }
        val hooked = hookInvokeSuspend(createGroupClass) { chain ->
            val list = extractSettingList(chain.thisObject)
            if (list != null) settingListLocal.set(list)
            try {
                chain.proceed()
            } finally {
                settingListLocal.remove()
            }
        }
        if (hooked) {
            Log.x("SponsorBlockPlayerMenu: hooked $createGroupClassName.invokeSuspend")
        } else {
            Log.w("SponsorBlockPlayerMenu: invokeSuspend not found on $createGroupClassName")
        }
    }

    private fun hookInvokeSuspend(startClass: Class<*>, callback: (io.github.libxposed.api.XposedInterface.Chain) -> Any?): Boolean {
        var cls: Class<*>? = startClass
        while (cls != null) {
            val method = cls.declaredMethods.firstOrNull {
                it.name == "invokeSuspend" && it.parameterCount == 1
            }
            if (method != null) {
                method.hookMethod(callback)
                return true
            }
            cls = cls.superclass
        }
        return false
    }

    private fun injectMainEntry(menuHost: Any, videoSettingType: Any?, kind: PlayerMenuKind) {
        val list = settingListLocal.get() ?: run {
            Log.w("SponsorBlockPlayerMenu: inject skipped, \$list ThreadLocal empty")
            return
        }
        val context = extractContext(menuHost) ?: run {
            Log.w("SponsorBlockPlayerMenu: inject skipped, context missing on ${menuHost.javaClass.name}")
            return
        }
        val settingType = videoSettingType ?: uiFactory.middleVideoSettingType() ?: return
        val index = list.size
        SponsorBlockMenuHost.setPlayerMenuKind(kind)
        uiFactory.createMainEntryRow(context, settingType)?.let {
            list.add(index, it)
            PlaybackMenuRows.injectAfterSponsorBlock(uiFactory, list, context, kind, index + 1)
            Log.x("SponsorBlockPlayerMenu: injected main + playback entries at index=$index kind=$kind")
        } ?: Log.w("SponsorBlockPlayerMenu: inject failed, row creation returned null")
    }

    private fun prependMainEntry(
        rows: MutableList<Any>,
        context: Context,
        settingType: Any,
        kind: PlayerMenuKind,
    ): List<Any> {
        uiFactory.createMainEntryRow(context, settingType)?.let {
            SponsorBlockMenuHost.setPlayerMenuKind(kind)
            rows.add(0, it)
            PlaybackMenuRows.injectAfterSponsorBlock(uiFactory, rows, context, kind, 1)
            Log.x("SponsorBlockPlayerMenu: prepended main + playback entries in fullscreen menu kind=$kind")
        } ?: Log.w("SponsorBlockPlayerMenu: prepend skipped, row creation failed")
        return rows
    }

    private fun hookFabFallback() {
        val widgetClass = "com.bilibili.playerbizcommonv2.widget.setting.PlayerSettingFunctionWidget2"
            .from(mClassLoader) ?: return
        widgetClass.hookMethod("onWidgetShow") { chain ->
            chain.proceed()
            if (uiFactory.isNativeAvailable()) return@hookMethod null
            val widget = chain.thisObject
            val context = extractContext(widget) ?: return@hookMethod null
            val anchor = widget as? ViewGroup ?: return@hookMethod null
            attachFab(context, anchor)
            null
        }
    }

    private fun attachFab(context: Context, anchor: ViewGroup) {
        if (!fabShown.compareAndSet(false, true)) return
        runCatching {
            val fab = TextView(context).apply {
                text = "空降"
                setTextColor(Color.WHITE)
                setBackgroundColor(0xAA000000.toInt())
                gravity = Gravity.CENTER
                val pad = (8 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                alpha = 0.85f
                setOnClickListener { SponsorBlockSubMenu.showMain(context) }
                setOnLongClickListener {
                    SponsorBlockController.setEnabled(!SponsorBlockPrefs.enabled)
                    Log.toast(if (SponsorBlockPrefs.enabled) "空降助手已开启" else "空降助手已关闭")
                    true
                }
            }
            val size = (48 * context.resources.displayMetrics.density).toInt()
            val params = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = (12 * context.resources.displayMetrics.density).toInt()
            }
            anchor.addView(fab, params)
            Log.x("SponsorBlockPlayerMenu: FAB fallback attached")
        }.onFailure {
            fabShown.set(false)
            Log.w("SponsorBlockPlayerMenu FAB failed: ${it.message}")
        }
    }

    private fun settingItemTypeName(settingItem: Any?): String? {
        val base = settingItem?.callMethodOrNull("getBase") ?: return null
        return (base.callMethodOrNull("getType") as? Enum<*>)?.name
    }

    private fun extractContext(host: Any): Context? =
        (host.callMethodOrNull("getMContext") as? Context)
            ?: (host.callMethodOrNull("getContext") as? Context)
            ?: host.javaClass.declaredFields.firstOrNull { Context::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }
                ?.get(host) as? Context

    private fun extractSettingList(coroutine: Any): MutableList<Any>? {
        val fields = coroutine.javaClass.declaredFields
        fields.firstOrNull { it.name == "\$list" }?.let { field ->
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return field.get(coroutine) as? MutableList<Any>
        }
        return fields
            .firstOrNull { List::class.java.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true }
            ?.get(coroutine) as? MutableList<Any>
    }
}
