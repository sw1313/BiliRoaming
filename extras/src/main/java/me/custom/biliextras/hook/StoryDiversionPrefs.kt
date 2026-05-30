package me.custom.biliextras.hook

import me.custom.biliextras.utils.ePrefs

/**
 * Per-item configuration for the vertical-stream commercial entries, all surfaced in the single
 * "竖屏导流入口设置" sub-menu. Two kinds of items live here:
 *
 *  - Diversion chip types ([gotoEntries]): the bottom-left "导流入口" chip
 *    (StoryDiversionEntryWidget) shows one entry distinguished by cartIconInfo.entryGoto
 *    (cart/game/ogv/vip/ad). Each type is an independent toggle.
 *  - Standalone overlays ([overlayEntries]): the floating shopping-cart container
 *    (StoryShopCartWidget) and the in-video ad CTA banner "应用 · 点击直达…" (StoryAdWidget).
 *
 * Shared between the hook (reads the toggles) and the settings dialog (writes them).
 */
object StoryDiversionPrefs {
    data class Entry(
        val key: String,
        val title: String,
        val shortTitle: String,
        val gotos: Set<String> = emptySet(),
    )

    const val KEY_SHOPCART = "block_story_shopcart"
    const val KEY_AD_OVERLAY = "block_story_ad_overlay"

    val gotoEntries = listOf(
        Entry("block_diversion_cart", "购物 / 视频同款 / 立即购买", "购物", setOf("cart", "anchor_nature")),
        Entry("block_diversion_game", "游戏推广入口", "游戏", setOf("game")),
        Entry("block_diversion_ogv", "番剧 / 影视推广入口", "番剧", setOf("ogv")),
        Entry("block_diversion_vip", "大会员推广入口", "大会员", setOf("vip")),
        Entry("block_diversion_ad", "广告推广入口", "广告", setOf("ad", "anchor_ad")),
    )

    val overlayEntries = listOf(
        Entry(KEY_SHOPCART, "购物车浮层", "购物车浮层"),
        Entry(KEY_AD_OVERLAY, "应用 · 点击直达广告浮层", "广告浮层"),
    )

    /** All items, in display order, for the settings dialog. */
    val allEntries = gotoEntries + overlayEntries

    fun isBlocked(key: String): Boolean = ePrefs.getBoolean(key, false)

    /** The union of entryGoto values that should be hidden, based on the enabled chip toggles. */
    fun blockedGotos(): Set<String> =
        gotoEntries.filter { isBlocked(it.key) }.flatMap { it.gotos }.toSet()

    fun shopCartBlocked(): Boolean = isBlocked(KEY_SHOPCART)

    fun adOverlayBlocked(): Boolean = isBlocked(KEY_AD_OVERLAY)

    /** Short labels of the currently-blocked items, for the settings summary. */
    fun blockedShortTitles(): List<String> =
        allEntries.filter { isBlocked(it.key) }.map { it.shortTitle }
}
