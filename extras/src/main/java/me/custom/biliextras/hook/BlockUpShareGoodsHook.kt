package me.custom.biliextras.hook

import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.utils.*

class BlockUpShareGoodsHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!ePrefs.getBoolean("block_up_share_goods", false)) return
        Log.d("startHook: BlockUpShareGoods")

        instance.viewUniteMossClass?.hookMethod("executeView", instance.viewUniteReqClass) { chain ->
            chain.proceed()?.also { handleViewReply(it, true) }
        }

        val viewMethod = if (instance.useNewMossFunc) "executeView" else "view"
        instance.viewMossClass?.hookMethod(viewMethod, instance.viewReqClass) { chain ->
            chain.proceed()?.also { handleViewReply(it, false) }
        }
    }

    private fun handleViewReply(viewReply: Any, isUnite: Boolean) {
        fun Any.isMerchandise() = callMethodAs<Boolean>("hasMerchandise")

        fun MutableList<Any>.filterModules() = removeAll { it.isMerchandise() }

        if (isUnite) {
            viewReply.callMethod("getTab")?.run {
                callMethod("ensureTabModuleIsMutable")
                val tabModuleList = callMethodAs<MutableList<Any>>("getTabModuleList")
                tabModuleList.firstOrNull { it.callMethod("hasIntroduction") == true }?.let {
                    it.callMethodAs<Any>("getIntroduction").run {
                        callMethod("ensureModulesIsMutable")
                        callMethodAs<MutableList<Any>>("getModulesList").filterModules()
                    }
                }
            }
            viewReply.runCatchingOrNull { callMethod("clearMerchandise") }
            return
        }

        viewReply.callMethod("getTab")?.run {
            callMethod("ensureTabModuleIsMutable")
            val tabModuleList = callMethodAs<MutableList<Any>>("getTabModuleList")
            tabModuleList.firstOrNull { it.callMethod("hasIntroduction") == true }?.let {
                it.callMethodAs<Any>("getIntroduction").run {
                    callMethod("ensureModulesIsMutable")
                    callMethodAs<MutableList<Any>>("getModulesList").filterModules()
                }
            }
        }
        viewReply.runCatchingOrNull { callMethod("clearMerchandise") }
    }
}
