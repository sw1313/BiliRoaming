package me.custom.biliextras.playback

/**
 * Icons from Bilibili in-app iconfont ([assets/icon_font/metadata.json]).
 * Format: `{name}@500` — passed as [com.bilibili.playerbizcommonv2.widget.setting.channel.VideoSettingSwitchComponent.a] iconRes.
 */
object PlaybackMenuIcons {
    /** 连播：官方播放器「自动连播」同类图标 */
    const val AUTO_NEXT = "autoplay-line@500"

    /** 媒体按键 / 耳机线控 */
    const val MEDIA_BUTTON = "headset-audio-line@500"

    /** 章节进度 / 分段标记 */
    const val CHAPTER_SEGMENT = "playtime-square-line@500"

    const val SCOPES = "list-select-line@500"
    const val PREFS = "playsetting-line@500"
    const val ORIENTATION = "arrow-expand-fullscreen-double-line@500"
    const val UP = "person-user-line@500"
    const val BLOCK_UP = "person-blacklist-line@500"
    const val TAG = "calendar-mark-line@500"
}
