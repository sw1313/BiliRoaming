package me.custom.biliextras.sponsorblock

import android.content.Context
import me.custom.biliextras.playback.PlaybackMenuActionHandler
import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.from
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

class SponsorBlockMenuUiFactory private constructor(private val classLoader: ClassLoader) {
    private val nativeClassesAvailable: Boolean by lazy {
        switchComponentClass != null &&
            descriptionComponentClass != null &&
            runningUiComponentClass != null &&
            videoSettingTypeClass != null &&
            mutableStateFlowMethod != null
    }

    private val runningUiComponentClass by lazy {
        "com.bilibili.app.gemini.base.ui.RunningUIComponent".from(classLoader)
    }
    private val switchComponentClass by lazy {
        "com.bilibili.playerbizcommonv2.widget.setting.channel.VideoSettingSwitchComponent".from(classLoader)
    }
    private val switchArgsClass by lazy {
        switchComponentClass?.declaredClasses?.firstOrNull { it.simpleName == "a" }
    }
    private val descriptionComponentClass by lazy {
        "com.bilibili.playerbizcommonv2.widget.setting.channel.VideoSettingDescriptionComponent".from(classLoader)
    }
    private val descriptionArgsClass by lazy {
        descriptionComponentClass?.declaredClasses?.firstOrNull { it.simpleName == "a" }
    }
    private val spacerComponentClass by lazy {
        "com.bilibili.playerbizcommonv2.widget.setting.channel.j".from(classLoader)
    }
    private val videoSettingTypeClass by lazy {
        "com.bilibili.playerbizcommonv2.widget.setting.channel.VideoSettingType".from(classLoader)
    }
    private val mutableStateFlowMethod by lazy {
        runCatching {
            Class.forName("kotlinx.coroutines.flow.StateFlowKt", true, classLoader)
                .getDeclaredMethod("MutableStateFlow", Any::class.java)
        }.getOrNull()
    }
    private val themeColorCache = ConcurrentHashMap<String, Int>()
    private var playerSettingBgColor: Int? = null
    private var playerSettingGraphBgColor: Int? = null
    private val constructorCache = ConcurrentHashMap<Class<*>, Constructor<*>>()

    private val themeColorClass by lazy {
        Class.forName("com.bilibili.lib.theme.R\$color", true, classLoader)
    }

    fun isNativeAvailable(): Boolean = nativeClassesAvailable

    /** 主菜单入口：状态文字 + 箭头；失败时回退为开关行（与早期可显示版本一致）。 */
    fun createMainEntryRow(context: Context, videoSettingType: Any): Any? {
        val fullscreenInline = SponsorBlockMenuHost.isFullscreenWidget
        runCatching {
            return createActionRow(
                title = "空降助手",
                icon = "skip-beginning-end-line@500",
                subtitle = mainMenuStatusText(),
                withArrow = true,
                videoSettingType = videoSettingType,
                actionId = SponsorBlockMenuActionHandler.ACTION_OPEN_MAIN,
                context = context,
                fullscreenInline = fullscreenInline,
            )
        }.onFailure {
            Log.w { "SponsorBlock menu entry (description) failed: ${it.cause?.message ?: it.message}" }
        }
        Log.w { "SponsorBlock menu entry: fallback to switch row" }
        return runCatching {
            createSwitchRow(
                "空降助手",
                "skip-beginning-end-line@500",
                videoSettingType,
                fullscreenInline = fullscreenInline,
            )
        }.onFailure {
            Log.w { "SponsorBlock menu entry (switch) failed: ${it.cause?.message ?: it.message}" }
        }.getOrNull()
    }

    /** 信息行：标题 + 副标题，无箭头（piliplus 式信息展示）。 */
    fun createInfoRow(
        title: String,
        subtitle: String,
        icon: String,
        videoSettingType: Any,
    ): Any {
        val boolFlow = mutableStateFlow(true)
        val textFlow = mutableStateFlow(subtitle)
        val argsClass = descriptionArgsClass ?: error("VideoSettingDescriptionComponent.a missing")
        val ctor = argsClass.primaryConstructor()
        val fn0Class = ctor.parameterTypes[7]
        val onClick = HostFunction0Proxy.create(classLoader, fn0Class) { /* info only */ }
        val colors = descriptionColors()
        val args = ctor.newInstance(
            title,
            "",
            icon,
            boolFlow,
            textFlow,
            "",
            "",
            onClick,
            null,
            colors.title,
            colors.subtitle,
            colors.background,
            colors.line,
            videoSettingType,
            null,
        )
        val row = descriptionComponentClass!!.getConstructor(argsClass).newInstance(args)
        return wrapRunningComponent(row)
    }

    fun createSettingsSwitchRow(
        title: String,
        icon: String,
        enabled: Boolean,
        toggleActionId: Int,
        videoSettingType: Any,
        fullscreenInline: Boolean = false,
    ): Any {
        val flow = mutableStateFlow(enabled)
        val argsClass = switchArgsClass ?: error("VideoSettingSwitchComponent.a missing")
        val ctor = argsClass.primaryConstructor()
        val fn0Class = ctor.parameterTypes[4]
        val switchColors = switchColors(fullscreenInline)
        val args = ctor.newInstance(
            title,
            "",
            icon,
            flow,
            SponsorBlockMenuActionHandler.createPrefToggle(classLoader, fn0Class, toggleActionId, flow),
            null,
            false,
            switchColors[0],
            switchColors[1],
            switchColors[2],
            switchColors[3],
            switchColors[4],
            switchColors[5],
            videoSettingType,
        )
        val switch = switchComponentClass!!.getConstructor(argsClass).newInstance(args)
        return wrapRunningComponent(switch)
    }

    fun createPlaybackSwitchRow(
        title: String,
        icon: String,
        enabled: Boolean,
        toggleActionId: Int,
        videoSettingType: Any,
        fullscreenInline: Boolean = false,
    ): Any {
        val flow = mutableStateFlow(enabled)
        val argsClass = switchArgsClass ?: error("VideoSettingSwitchComponent.a missing")
        val ctor = argsClass.primaryConstructor()
        val fn0Class = ctor.parameterTypes[4]
        val switchColors = switchColors(fullscreenInline)
        val args = ctor.newInstance(
            title,
            "",
            icon,
            flow,
            PlaybackMenuActionHandler.createToggle(classLoader, fn0Class, toggleActionId, flow),
            null,
            false,
            switchColors[0],
            switchColors[1],
            switchColors[2],
            switchColors[3],
            switchColors[4],
            switchColors[5],
            videoSettingType,
        )
        val switch = switchComponentClass!!.getConstructor(argsClass).newInstance(args)
        return wrapRunningComponent(switch)
    }

    fun createPlaybackActionRow(
        title: String,
        icon: String,
        subtitle: String,
        withArrow: Boolean,
        videoSettingType: Any,
        actionId: Int,
        context: Context,
        fullscreenInline: Boolean = false,
    ): Any = createActionRow(
        title = title,
        icon = icon,
        subtitle = subtitle,
        withArrow = withArrow,
        videoSettingType = videoSettingType,
        actionId = actionId,
        context = context,
        onClickOverride = run {
            val argsClass = descriptionArgsClass ?: error("VideoSettingDescriptionComponent.a missing")
            val ctor = argsClass.primaryConstructor()
            val fn0Class = ctor.parameterTypes[7]
            PlaybackMenuActionHandler.create(classLoader, fn0Class, actionId, context)
        },
        fullscreenInline = fullscreenInline,
    )

    fun createSwitchRow(
        title: String,
        icon: String,
        videoSettingType: Any,
        fullscreenInline: Boolean = false,
    ): Any {
        val flow = mutableStateFlow(SponsorBlockPrefs.enabled)
        val argsClass = switchArgsClass ?: error("VideoSettingSwitchComponent.a missing")
        val ctor = argsClass.primaryConstructor()
        val fn0Class = ctor.parameterTypes[4]
        val switchColors = switchColors(fullscreenInline)
        val args = ctor.newInstance(
            title,
            "",
            icon,
            flow,
            SponsorBlockMenuActionHandler.createToggle(classLoader, fn0Class, flow),
            null,
            false,
            switchColors[0],
            switchColors[1],
            switchColors[2],
            switchColors[3],
            switchColors[4],
            switchColors[5],
            videoSettingType,
        )
        val switch = switchComponentClass!!.getConstructor(argsClass).newInstance(args)
        return wrapRunningComponent(switch)
    }

    fun createActionRow(
        title: String,
        icon: String,
        subtitle: String,
        withArrow: Boolean,
        videoSettingType: Any,
        actionId: Int,
        context: Context,
        segmentIndex: Int? = null,
        voteType: Int? = null,
        onClickOverride: Any? = null,
        fullscreenInline: Boolean = false,
    ): Any {
        val boolFlow = mutableStateFlow(true)
        val textFlow = mutableStateFlow(subtitle)
        val argsClass = descriptionArgsClass ?: error("VideoSettingDescriptionComponent.a missing")
        val ctor = argsClass.primaryConstructor()
        val fn0Class = ctor.parameterTypes[7]
        val onClick = onClickOverride ?: when {
            voteType != null -> SponsorBlockMenuActionHandler.createVote(
                classLoader,
                fn0Class,
                context,
                segmentIndex ?: 0,
                voteType,
            )
            segmentIndex != null -> SponsorBlockMenuActionHandler.createIndexed(
                classLoader,
                fn0Class,
                actionId,
                context,
                segmentIndex,
            )
            else -> SponsorBlockMenuActionHandler.create(classLoader, fn0Class, actionId, context)
        }
        val colors = descriptionColors(fullscreenInline)
        val args = ctor.newInstance(
            title,
            "",
            icon,
            boolFlow,
            textFlow,
            "",
            if (withArrow) "arrow-forward-right-line@900" else "",
            onClick,
            null,
            colors.title,
            colors.subtitle,
            colors.background,
            colors.line,
            videoSettingType,
            null,
        )
        val row = descriptionComponentClass!!.getConstructor(argsClass).newInstance(args)
        return wrapRunningComponent(row)
    }

    fun createSubmitActionRow(
        title: String,
        icon: String,
        subtitle: String,
        withArrow: Boolean,
        videoSettingType: Any,
        actionId: Int,
        context: Context,
        index: Int = 0,
        subAction: Int = 0,
    ): Any {
        val argsClass = descriptionArgsClass ?: error("VideoSettingDescriptionComponent.a missing")
        val ctor = argsClass.primaryConstructor()
        val fn0Class = ctor.parameterTypes[7]
        val onClick = when (actionId) {
            SponsorBlockSubmitActionHandler.OPEN_EXISTING,
            SponsorBlockSubmitActionHandler.OPEN_DRAFT,
            -> SponsorBlockSubmitActionHandler.createIndexed(
                classLoader, fn0Class, actionId, context, index,
            )
            SponsorBlockSubmitActionHandler.DRAFT_ACTION,
            SponsorBlockSubmitActionHandler.EXISTING_ACTION,
            -> SponsorBlockSubmitActionHandler.createSub(
                classLoader, fn0Class, actionId, context, index, subAction,
            )
            SponsorBlockSubmitActionHandler.BACK ->
                SponsorBlockSubmitActionHandler.createBack(
                    classLoader, fn0Class, context, subAction, index,
                )
            else -> SponsorBlockSubmitActionHandler.create(classLoader, fn0Class, actionId, context)
        }
        return createDescriptionRow(
            title, icon, subtitle, withArrow, videoSettingType, onClick,
        )
    }

    fun createSubmitTimeActionRow(
        title: String,
        icon: String,
        subtitle: String,
        withArrow: Boolean,
        videoSettingType: Any,
        context: Context,
        draftIndex: Int,
        timeField: Int,
        subAction: Int,
    ): Any {
        val argsClass = descriptionArgsClass ?: error("VideoSettingDescriptionComponent.a missing")
        val ctor = argsClass.primaryConstructor()
        val fn0Class = ctor.parameterTypes[7]
        val onClick = SponsorBlockSubmitActionHandler.createTime(
            classLoader, fn0Class, context, draftIndex, timeField, subAction,
        )
        return createDescriptionRow(title, icon, subtitle, withArrow, videoSettingType, onClick)
    }

    fun createSubmitTimeActionRowExisting(
        title: String,
        icon: String,
        subtitle: String,
        withArrow: Boolean,
        videoSettingType: Any,
        context: Context,
        existingIndex: Int,
        timeField: Int,
        subAction: Int,
    ): Any {
        val argsClass = descriptionArgsClass ?: error("VideoSettingDescriptionComponent.a missing")
        val ctor = argsClass.primaryConstructor()
        val fn0Class = ctor.parameterTypes[7]
        val onClick = SponsorBlockSubmitActionHandler.createExistingTime(
            classLoader, fn0Class, context, existingIndex, timeField, subAction,
        )
        return createDescriptionRow(title, icon, subtitle, withArrow, videoSettingType, onClick)
    }

    private fun createDescriptionRow(
        title: String,
        icon: String,
        subtitle: String,
        withArrow: Boolean,
        videoSettingType: Any,
        onClick: Any,
    ): Any {
        val boolFlow = mutableStateFlow(true)
        val textFlow = mutableStateFlow(subtitle)
        val argsClass = descriptionArgsClass ?: error("VideoSettingDescriptionComponent.a missing")
        val ctor = argsClass.primaryConstructor()
        val colors = descriptionColors()
        val args = ctor.newInstance(
            title,
            "",
            icon,
            boolFlow,
            textFlow,
            "",
            if (withArrow) "arrow-forward-right-line@900" else "",
            onClick,
            null,
            colors.title,
            colors.subtitle,
            colors.background,
            colors.line,
            videoSettingType,
            null,
        )
        val row = descriptionComponentClass!!.getConstructor(argsClass).newInstance(args)
        return wrapRunningComponent(row)
    }

    fun createSpacer(heightDp: Int): Any {
        val spacer = spacerComponentClass!!.getConstructor(Int::class.javaPrimitiveType).newInstance(heightDp)
        return wrapRunningComponent(spacer)
    }

    fun videoSettingTypeForIndex(index: Int, total: Int): Any {
        val constants = videoSettingTypeClass?.enumConstants ?: emptyArray()
        val name = when {
            total <= 1 -> "TOP_BOTTOM"
            index == 0 -> "TOP"
            index == total - 1 -> "BOTTOM"
            else -> "MIDDLE"
        }
        return constants.firstOrNull { (it as Enum<*>).name == name }
            ?: constants.firstOrNull { (it as Enum<*>).name == "MIDDLE" }
            ?: constants.first()
    }

    fun middleVideoSettingType(): Any? = videoSettingTypeForIndex(0, 3)

    private fun wrapRunningComponent(inner: Any): Any {
        val ctor = runningUiComponentClass!!.primaryConstructor()
        return ctor.newInstance(inner, null)
    }

    private fun mutableStateFlow(value: Any): Any {
        val method = mutableStateFlowMethod ?: error("StateFlowKt missing")
        return method.invoke(null, value)!!
    }

    private fun Class<*>.primaryConstructor(): Constructor<*> =
        constructorCache.getOrPut(this) {
            val marker = "kotlin.jvm.internal.DefaultConstructorMarker"
            declaredConstructors.firstOrNull { ctor ->
                ctor.parameterTypes.none { it.name == marker }
            } ?: error("primary constructor missing on $name")
        }

    private fun themeColor(name: String): Int =
        themeColorCache.getOrPut(name) {
            themeColorClass.getField(name).getInt(null)
        }

    private fun themeColorOrNull(name: String): Int? =
        runCatching { themeColor(name) }.getOrNull()

    /** 横屏全屏菜单与 PlayerSettingFunctionWidget2 一致：白字 + 半透明灰黑底。 */
    private data class DescriptionColors(
        val title: Int,
        val subtitle: Int,
        val background: Int,
        val line: Int,
    )

    private fun descriptionColors(fullscreenInline: Boolean = false): DescriptionColors {
        if (!fullscreenInline) {
            return DescriptionColors(
                themeColor("Text1"),
                themeColor("Text3"),
                themeColor("Bg1_float"),
                themeColor("Line_regular"),
            )
        }
        return DescriptionColors(
            themeColor("Wh0_u"),
            themeColor("Ga5_u"),
            playerSettingBackgroundColor(),
            themeColor("Ga8_u"),
        )
    }

    /** title, background, line, trackOn, thumbOn, trackOff */
    private fun switchColors(fullscreenInline: Boolean = false): IntArray {
        if (!fullscreenInline) {
            return intArrayOf(
                themeColor("Text1"),
                themeColor("Bg1_float"),
                themeColor("Line_regular"),
                themeColor("Graph_white"),
                themeColor("Pi5"),
                themeColor("Graph_bg_thick"),
            )
        }
        val white = themeColor("Wh0_u")
        return intArrayOf(
            white,
            playerSettingBackgroundColor(),
            themeColor("Ga8_u"),
            white,
            themeColorOrNull("Pi5_u") ?: themeColor("Pi5"),
            playerSettingGraphBgColor(),
        )
    }

    private fun playerSettingBackgroundColor(): Int {
        playerSettingBgColor?.let { return it }
        val resolved = resolvePlayerSettingColor("v") ?: themeColorOrNull("Ga9_u") ?: themeColor("Bg2_float")
        playerSettingBgColor = resolved
        return resolved
    }

    private fun playerSettingGraphBgColor(): Int {
        playerSettingGraphBgColor?.let { return it }
        val resolved = resolvePlayerSettingColor("H") ?: themeColor("Graph_bg_thick")
        playerSettingGraphBgColor = resolved
        return resolved
    }

    /** sd3.c 中横屏播放器设置行的背景/轨道色（字段名随版本混淆，按短名匹配）。 */
    private fun resolvePlayerSettingColor(shortName: String): Int? = runCatching {
        val clazz = Class.forName("sd3.c", true, classLoader)
        clazz.declaredFields.firstOrNull { field ->
            Modifier.isStatic(field.modifiers) &&
                field.type == Int::class.javaPrimitiveType &&
                (field.name == shortName || field.name.endsWith(shortName))
        }?.apply { isAccessible = true }?.getInt(null)
    }.getOrNull()

    companion object {
        private val factories = ConcurrentHashMap<ClassLoader, SponsorBlockMenuUiFactory>()

        @JvmStatic
        fun forClassLoader(classLoader: ClassLoader): SponsorBlockMenuUiFactory =
            factories.getOrPut(classLoader) { SponsorBlockMenuUiFactory(classLoader) }

        @JvmStatic
        fun mainMenuStatusText(): String {
            val enabled = SponsorBlockPrefs.enabled
            val count = SponsorBlockController.segments.size
            return when {
                !enabled -> "已关闭"
                count == 0 -> "已开启"
                else -> "已开启 · $count 个片段"
            }
        }

        @JvmStatic
        fun submenuInfoText(): String {
            val count = SponsorBlockController.segments.size
            val current = SponsorBlockController.findSegmentAtTimeMs(
                SponsorBlockController.readPlaybackPositionMs()
                    ?: SponsorBlockController.playbackPositionMs,
            )
            return when {
                count == 0 -> "当前视频暂无片段"
                current != null -> {
                    val cat = SponsorBlockCategory.titleOf(current.category)
                    "$count 个片段 · 播放头在 $cat"
                }
                else -> "$count 个片段 · 播放头不在片段内"
            }
        }

        @JvmStatic
        fun readBooleanFlow(flow: Any?): Boolean? {
            if (flow == null) return null
            val getValue = flow.javaClass.methods.firstOrNull {
                it.name == "getValue" && it.parameterCount == 0
            }
            return runCatching { getValue?.invoke(flow) as? Boolean }.getOrNull()
        }

        @JvmStatic
        fun updateBooleanFlow(flow: Any?, value: Boolean) {
            if (flow == null) return
            val setValue = flow.javaClass.methods.firstOrNull {
                it.name == "setValue" && it.parameterCount == 1
            } ?: flow.javaClass.methods.firstOrNull {
                it.name == "tryEmit" && it.parameterCount == 1
            }
            runCatching { setValue?.invoke(flow, value) }
        }

        @JvmStatic
        fun updateStatusFlow(flow: Any?, label: String) {
            if (flow == null) return
            val setValue = flow.javaClass.methods.firstOrNull {
                it.name == "setValue" && it.parameterCount == 1
            } ?: flow.javaClass.methods.firstOrNull {
                it.name == "tryEmit" && it.parameterCount == 1
            }
            runCatching { setValue?.invoke(flow, label) }
        }
    }
}
