package me.custom.biliextras.hook

import me.custom.biliextras.utils.ePrefs

/** Story feed UP block list — matching uses mid only; nicknames are queried for display. */
object StoryUpBlockPrefs {
    const val KEY_ENABLED = "block_story_up"
    const val KEY_MIDS = "story_block_up_mids"
    const val DEFAULT_QUARK_MID = 405724215L
    private val midLineRegex = Regex("""\((\d{1,20})\)\s*$""")

    fun enabled(): Boolean = ePrefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(value: Boolean) {
        ePrefs.edit().putBoolean(KEY_ENABLED, value).commit()
    }

    fun blockedMids(): Set<Long> = parseMids(rawMidsText()).toSet()

    fun orderedBlockedMids(): List<Long> = parseInputLines(rawMidsText())

    fun resolveInput(input: String): Long? = parseLine(input.trim())

    fun isBlocked(mid: Long): Boolean = enabled() && mid > 0L && mid in blockedMids()

    fun containsMid(mid: Long): Boolean = mid > 0L && mid in blockedMids()

    fun rawMidsText(): String =
        ePrefs.getString(KEY_MIDS, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_QUARK_MID.toString()

    fun saveMidsText(text: String) {
        val mids = parseInputLines(text)
        val normalized = mids.joinToString("\n")
        ePrefs.edit().putString(KEY_MIDS, normalized).commit()
        StoryUpCardApi.prefetchNamesAsync(mids)
    }

    fun addMid(mid: Long) {
        if (mid <= 0L) return
        val mids = blockedMids().toMutableSet()
        if (!mids.add(mid)) return
        saveMidsText(mids.joinToString("\n"))
    }

    fun removeMid(mid: Long) {
        if (mid <= 0L) return
        val mids = blockedMids().toMutableSet()
        if (!mids.remove(mid)) return
        saveMidsText(mids.joinToString("\n"))
    }

    /** One line per mid for settings editor; stored value is mid-only. */
    fun editorText(): String = rawMidsText()

    fun displaySummary(): String {
        val mids = blockedMids().sorted()
        if (mids.isEmpty()) return "未屏蔽任何 UP"
        return mids.joinToString("、") { StoryUpCardApi.formatDisplay(it) }
    }

    fun parseInputLines(text: String): List<Long> {
        val result = LinkedHashSet<Long>()
        text.lineSequence().forEach { line ->
            parseLine(line)?.let { result.add(it) }
        }
        return result.toList()
    }

    private fun parseMids(text: String): Set<Long> = parseInputLines(text).toSet()

    private fun parseLine(line: String): Long? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        midLineRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        trimmed.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        return StoryUpCardApi.resolveMidByNickname(trimmed)
    }
}
