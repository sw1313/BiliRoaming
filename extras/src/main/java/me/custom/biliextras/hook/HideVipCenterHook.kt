package me.custom.biliextras.hook

import android.os.Bundle
import android.view.View
import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.utils.*
import java.lang.reflect.Field
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

class HideVipCenterHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    companion object {
        // Bilibili 8.39.x AccountMine VIP fields (from APK analysis)
        private val drawerVipSectionFields = arrayOf(
            "vipSection",
            "vipSectionV2",
            "vipSectionV3",
            "vipSectionRight",
            "modularVipSection",
            "vipModule",
            "vipModuleV2",
        )
        private val drawerVipCardKeywords = arrayOf(
            "section", "card", "banner", "entrance", "center", "module", "revision", "right", "buy", "member",
        )
    }
    private val homeVipModuleManagerField by lazy {
        instance.homeUserCenterClass?.declaredFields?.firstOrNull {
            it.type.name.contains("MineVipModuleManager")
        }?.apply { isAccessible = true }
    }
    private val vipManagerBindingFieldCache = ConcurrentHashMap<Class<*>, Field?>()
    private val bindingViewFieldsCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val vipFieldCache = ConcurrentHashMap<Class<*>, List<Pair<Field, Boolean>>>()

    override fun startHook() {
        if (!ePrefs.getBoolean("hide_vip_center", false)) return
        Log.d("startHook: HideVipCenter")

        hookOfficialVipRefactoringManager()
        hookVipBanner()
        hookMineVipModuleManager()
        hookMineVipEntranceView()
        hookAccountMineJson()
    }

    private fun hookOfficialVipRefactoringManager() {
        val accountMineClass = "tv.danmaku.bili.ui.main2.api.AccountMine".findClassOrNull(mClassLoader)
            ?: return
        val managerClass = "tv.danmaku.bili.ui.main2.mine.f0".findClassOrNull(mClassLoader)
            ?: return
        val renderMethod = managerClass.declaredMethods.firstOrNull { method ->
            method.name == "g" &&
                method.parameterCount == 4 &&
                method.parameterTypes.firstOrNull() == accountMineClass
        } ?: return
        renderMethod.hookMethod { chain ->
            chain.args.firstOrNull()?.clearVipCenterData()
            chain.proceed()
        }
        Log.d("HideVipCenter: hooked official vip refactoring manager ${managerClass.name}#${renderMethod.name}")
    }

    private fun hookVipBanner() {
        val homeUserCenterClass = instance.homeUserCenterClass ?: return
        homeUserCenterClass.hookMethod("onViewCreated", View::class.java, Bundle::class.java) { chain ->
            chain.proceed()
            val vipModuleManager = homeVipModuleManagerField?.get(chain.thisObject)
            vipModuleManager?.hideVipManagerRoot()
            null
        }
    }

    private fun hookMineVipModuleManager() {
        val accountMineClass = "tv.danmaku.bili.ui.main2.api.AccountMine".findClassOrNull(mClassLoader)
            ?: return
        val mineVipModuleManagerClass = "tv.danmaku.bili.ui.main2.mine.modularvip.MineVipModuleManager"
            .findClassOrNull(mClassLoader) ?: return

        val methods = mineVipModuleManagerClass.declaredMethods
            .filter {
                it.returnType == Void.TYPE &&
                    it.parameterCount == 2 &&
                    it.parameterTypes[0] == accountMineClass &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
        Log.d("MineVipModuleManager hook methods: ${methods.joinToString { it.name }}")
        methods.forEach { method ->
            method.hookMethod { chain ->
                chain.thisObject?.hideVipManagerRoot()
                chain.args.getOrNull(0)?.clearVipCenterData()
                null
            }
        }
    }

    private fun hookMineVipEntranceView() {
        val accountMineClass = "tv.danmaku.bili.ui.main2.api.AccountMine".findClassOrNull(mClassLoader)
            ?: return
        val mineVipEntranceViewClass = "tv.danmaku.bili.ui.main2.mine.widgets.MineVipEntranceView"
            .findClassOrNull(mClassLoader) ?: return

        val methods = mineVipEntranceViewClass.declaredMethods.filter { method ->
            method.returnType == Void.TYPE &&
                (
                    method.name == "setData" ||
                        method.parameterTypes.any { it == accountMineClass }
                    )
        }
        Log.d("MineVipEntranceView hook methods: ${methods.joinToString { it.name }}")
        methods.forEach { method ->
            method.hookMethod { chain ->
                (chain.thisObject as? View)?.visibility = View.GONE
                chain.args.forEach { arg ->
                    when {
                        arg?.javaClass == accountMineClass -> arg.clearVipCenterData()
                        arg is View -> arg.visibility = View.GONE
                    }
                }
                null
            }
        }
    }

    private fun Any.hideVipManagerRoot() {
        runCatching {
            val bindingField = vipManagerBindingFieldCache.getOrPut(javaClass) {
                javaClass.declaredFields.firstOrNull {
                    it.type.name == "u53.t" || it.type.name.endsWith(".t")
                }?.apply { isAccessible = true }
            }
            val binding = bindingField?.get(this@hideVipManagerRoot) ?: return
            (binding.callMethodOrNull("getRoot") as? View)?.visibility = View.GONE
            (binding.callMethodOrNull("a") as? View)?.visibility = View.GONE
            bindingViewFieldsCache.getOrPut(binding.javaClass) {
                binding.javaClass.declaredFields.filter {
                    View::class.java.isAssignableFrom(it.type)
                }.onEach { it.isAccessible = true }
            }.forEach { field ->
                (field.get(binding) as? View)?.visibility = View.GONE
            }
        }.onFailure {
            Log.e(it)
        }
    }

    private fun hookAccountMineJson() {
        val accountMineClass = "tv.danmaku.bili.ui.main2.api.AccountMine".findClassOrNull(mClassLoader)
            ?: return
        val fastJsonClass = instance.fastJsonClass ?: return

        fastJsonClass.hookMethod(
            instance.fastJsonParse(),
            String::class.java,
            Type::class.java,
            Int::class.javaPrimitiveType,
            "com.alibaba.fastjson.parser.Feature[]",
        ) { chain ->
            val origResult = chain.proceed()
            var result = origResult ?: return@hookMethod null
            if (result.javaClass == instance.generalResponseClass) {
                result = result.getObjectField("data") ?: return@hookMethod origResult
            }
            if (result.javaClass != accountMineClass) return@hookMethod origResult

            result.clearVipCenterData()
            origResult
        }
    }

    private fun Any.clearVipCenterData() {
        getObjectFieldOrNullAs<MutableList<Any?>?>("sectionListV2")?.removeAll { sections ->
            sections?.isVipCenterSection() == true
        }
        getObjectFieldOrNullAs<MutableList<Any?>?>("sectionList")?.removeAll { sections ->
            sections?.isVipCenterSection() == true
        }
        hideExtraDrawerFields()
        runCatching { setBooleanField("useModularVipSection", false) }
    }

    private fun isVipCenterButton(uri: String?, text: String?) =
        uri.orEmpty().contains("user_center/vip", ignoreCase = true) ||
            uri.orEmpty().contains("bigfun.bilibili.com", ignoreCase = true) ||
            uri.orEmpty().contains("vip", ignoreCase = true) ||
            text.orEmpty().contains("会员中心") ||
            text.orEmpty().contains("會員中心") ||
            text.orEmpty().contains("大会员") ||
            text.orEmpty().contains("大會員")

    private fun Any.isVipCenterSection(): Boolean {
        val checked = mutableSetOf<Any>()

        fun inspect(value: Any?, depth: Int = 0): Boolean {
            if (value == null || depth > 1) return false
            if (!checked.add(value)) return false
            if (value is CharSequence) {
                val text = value.toString()
                return isVipCenterButton(text, text)
            }
            return runCatching {
                value.javaClass.declaredFields.any { field ->
                    if (field.type.isPrimitive) return@any false
                    field.isAccessible = true
                    val fieldName = field.name.lowercase()
                    val fieldValue = field.get(value)
                    val nameLooksVip = fieldName.contains("vip") &&
                        drawerVipCardKeywords.any { fieldName.contains(it) }
                    nameLooksVip || inspect(fieldValue, depth + 1)
                }
            }.getOrDefault(false)
        }

        return inspect(this)
    }

    private fun Any.hideExtraDrawerFields() {
        vipFieldCache.getOrPut(javaClass) {
            javaClass.declaredFields.mapNotNull { field ->
                if (field.type.isPrimitive) return@mapNotNull null
                val lowerFieldName = field.name.lowercase()
                val knownField = drawerVipSectionFields.any { it.equals(field.name, ignoreCase = true) }
                if (!knownField &&
                    (!lowerFieldName.contains("vip") ||
                        drawerVipCardKeywords.none { lowerFieldName.contains(it) })
                ) return@mapNotNull null
                field.isAccessible = true
                field to knownField
            }
        }.forEach { (field, knownField) ->
            val value = runCatching { field.get(this) }.getOrNull()
            if (!knownField && value?.isVipCenterSection() != true) return@forEach
            runCatching { field.set(this, null) }
            value?.stripVipCenterButton()
        }
    }

    private fun Any.stripVipCenterButton() {
        val button = getObjectFieldOrNull("button") ?: return
        val buttonText = button.getObjectFieldOrNull("text")?.toString() ?: return
        val jumpUrl = runCatching { button.getObjectFieldAs<String>("jumpUrl") }.getOrNull()
        if (!isVipCenterButton(jumpUrl, buttonText)) return
        setObjectField("button", null)
    }
}
