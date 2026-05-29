package me.custom.biliextras.sponsorblock

object SponsorBlockCategory {
    data class Category(
        val id: String,
        val title: String,
        val shortTitle: String,
        val description: String,
        val defaultColor: Int,
        val defaultMode: SkipMode,
    )

    enum class SkipMode(val value: String, val title: String) {
        Always("always", "总是跳过"),
        Once("once", "跳过一次"),
        Manual("manual", "手动跳过"),
        ShowOnly("show", "仅显示"),
        Disabled("disabled", "禁用"),
    }

    val all = listOf(
        Category(
            "sponsor",
            "赞助/恰饭",
            "赞助",
            "付费推广、推荐和直接广告。不是自我推广或免费提及他们喜欢的商品/创作者/网站/产品。",
            0xFF00D400.toInt(),
            SkipMode.Always,
        ),
        Category(
            "selfpromo",
            "无偿/自我推广",
            "推广",
            "类似于“赞助广告”，但无报酬或是自我推广。包括有关商品、捐赠的部分或合作者的信息。",
            0xFFFFFF00.toInt(),
            SkipMode.Once,
        ),
        Category(
            "exclusive_access",
            "独家访问/抢先体验",
            "品牌合作",
            "仅用于对整个视频进行标记。适用于展示UP主免费或获得补贴后使用的产品、服务或场地的视频。",
            0xFF008A5C.toInt(),
            SkipMode.Disabled,
        ),
        Category(
            "interaction",
            "三连/互动提醒",
            "三连提醒",
            "视频中间简短提醒观众来一键三连或关注。如果片段较长，或是有具体内容，则应分类为自我推广。",
            0xFFCC00FF.toInt(),
            SkipMode.Once,
        ),
        Category(
            "poi_highlight",
            "精彩时刻/重点",
            "精彩时刻",
            "大部分人都在寻找的空降时间。类似于“封面在12:34”的评论。",
            0xFFFF1684.toInt(),
            SkipMode.ShowOnly,
        ),
        Category(
            "intro",
            "过场/开场动画",
            "开场动画",
            "没有实际内容的间隔片段。可以是暂停、静态帧或重复动画。不适用于包含内容的过场。",
            0xFF00FFFF.toInt(),
            SkipMode.Once,
        ),
        Category(
            "outro",
            "鸣谢/结束画面",
            "片尾",
            "致谢画面或片尾画面。不包含内容的结尾。",
            0xFF0202ED.toInt(),
            SkipMode.Once,
        ),
        Category(
            "preview",
            "回顾/概要",
            "预览",
            "展示此视频或同系列视频将出现的画面集锦，片段中所有内容都将在之后的正片中再次出现。",
            0xFF008FD6.toInt(),
            SkipMode.Once,
        ),
        Category(
            "padding",
            "填充内容/前黑/后黑",
            "填充内容",
            "搬运视频片头片尾的纯粹填充内容，如黑屏或无关画面，与视频主体内容无实际意义和关联。",
            0xFF222222.toInt(),
            SkipMode.Always,
        ),
        Category(
            "filler",
            "离题闲聊/玩笑",
            "离题",
            "仅作为填充内容或增添趣味而添加的离题片段。这是一个较激进的分类。",
            0xFF7300FF.toInt(),
            SkipMode.Disabled,
        ),
        Category(
            "music_offtopic",
            "音乐:非音乐部分",
            "非音乐",
            "仅用于音乐视频。此分类只能用于音乐视频中未包括于其他分类的部分。",
            0xFFFF9900.toInt(),
            SkipMode.Always,
        ),
    )

    val defaultModes = all.associate { it.id to it.defaultMode }
    val defaultColors = all.associate { it.id to it.defaultColor }
    val defaultEnabled = defaultModes.filterValues { it != SkipMode.Disabled }.keys

    fun titleOf(id: String) = all.firstOrNull { it.id == id }?.title ?: id

    fun shortTitleOf(id: String) = all.firstOrNull { it.id == id }?.shortTitle ?: titleOf(id)

    fun modeOf(value: String?) = when (value) {
        "auto" -> SkipMode.Always
        "showOnly" -> SkipMode.ShowOnly
        "skipManually" -> SkipMode.Manual
        else -> SkipMode.entries.firstOrNull { it.value == value }
    }
}
