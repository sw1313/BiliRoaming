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
    const val KEY_OGV_BANGUMI = "block_story_ogv_bangumi"
    const val KEY_OGV_MOVIE = "block_story_ogv_movie"
    const val KEY_CHARGE_ENTRY = "block_story_charge_entry"

    val gotoEntries = listOf(
        Entry("block_diversion_cart", "购物 / 视频同款 / 立即购买", "购物", setOf("cart", "anchor_nature")),
        Entry("block_diversion_game", "游戏推广入口", "游戏", setOf("game")),
        Entry("block_diversion_vip", "大会员推广入口", "大会员", setOf("vip")),
        Entry("block_diversion_ad", "广告推广入口", "广告", setOf("ad", "anchor_ad")),
        Entry("block_diversion_topic", "话题入口", "话题", setOf("topic_new", "topic")),
        Entry("block_diversion_music", "音乐入口", "音乐", setOf("music_new", "music")),
        Entry("block_diversion_btool", "智能成片 / 一键生成大片入口", "智能成片", setOf("b_tool")),
    )

    /**
     * 影视溯源「胶片卡」(StoryDetail collection, cmd="ogv-season"): one chip rendered as
     * "<类型> | <片名>" e.g. "电影 | 大创业家". Both toggles target the SAME chip and differ only by
     * the leading 类型 label (= collection.title). [Entry.gotos] holds the category keywords matched
     * against that label. Nulling the collection removes both renderings (OGV bar + title chip).
     */
    val ogvSeasonEntries = listOf(
        Entry(KEY_OGV_BANGUMI, "番剧入口（影视溯源胶片卡）", "番剧", setOf("番剧", "国创", "动画")),
        Entry(KEY_OGV_MOVIE, "影视入口（影视溯源胶片卡）", "影视", setOf("电影", "影视", "电视剧", "纪录片", "综艺")),
    )

    /**
     * Other text-label-matched diversion chips that share the same "<类型> | <片名>" rendering but
     * are not 影视溯源. The 充电 entry's entryGoto is "charge" (not unique to it — the OGV chip can
     * also use "charge"), so it too is matched by its entryText label "充电".
     */
    val textEntries = listOf(
        Entry(KEY_CHARGE_ENTRY, "充电专属入口", "充电", setOf("充电")),
    )

    val overlayEntries = listOf(
        Entry(KEY_SHOPCART, "购物车浮层", "购物车浮层"),
        Entry(KEY_AD_OVERLAY, "应用 · 点击直达广告浮层", "广告浮层"),
    )

    /** All items, in display order, for the settings dialog. */
    val allEntries = gotoEntries + ogvSeasonEntries + textEntries + overlayEntries

    fun isBlocked(key: String): Boolean = ePrefs.getBoolean(key, false)

    /** The union of entryGoto values that should be hidden, based on the enabled chip toggles. */
    fun blockedGotos(): Set<String> =
        gotoEntries.filter { isBlocked(it.key) }.flatMap { it.gotos }.toSet()

    fun shopCartBlocked(): Boolean = isBlocked(KEY_SHOPCART)

    fun adOverlayBlocked(): Boolean = isBlocked(KEY_AD_OVERLAY)

    /**
     * Category keyword sets for the enabled 番剧/电影 ogv-season toggles (matched against
     * collection.title for the OGV season bar). Empty when neither toggle is on.
     */
    fun blockedOgvCategories(): List<Set<String>> =
        ogvSeasonEntries.filter { isBlocked(it.key) }.map { it.gotos }

    /**
     * Union of entryText keyword labels to hide on the diversion chip (StoryDiversionEntryWidget):
     * the enabled 番剧/电影「影视溯源胶片卡」plus the 充电 entry. Matched against cartIconInfo.entryText.
     */
    fun blockedTextLabels(): Set<String> =
        (ogvSeasonEntries + textEntries).filter { isBlocked(it.key) }.flatMap { it.gotos }.toSet()

    /** Short labels of the currently-blocked items, for the settings summary. */
    fun blockedShortTitles(): List<String> =
        allEntries.filter { isBlocked(it.key) }.map { it.shortTitle }
}
