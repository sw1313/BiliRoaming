package me.custom.biliextras.playback

import me.custom.biliextras.hook.ForegroundAutoNextPrefs

object ForegroundAutoNextScopeSummary {
    fun short(): String =
        ForegroundAutoNextPrefs.enabledShortTitles().joinToString("、").ifBlank { "未选类型" }

    fun scopesSubtitle(): String = "已启用：${short()}"

    fun prefsSubtitle(): String = ForegroundAutoNextPrefs.videoPrefsSummary()
}
