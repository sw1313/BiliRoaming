package me.custom.biliextras.sponsorblock

import me.custom.biliextras.utils.ePrefs
import java.security.MessageDigest
import java.util.UUID

object SponsorBlockPrefs {
    const val KEY_ENABLED = "sponsorblock_enable"
    const val KEY_SHOW_TOAST = "sponsorblock_show_toast"
    const val KEY_SHOW_PROGRESS = "sponsorblock_show_progress"
    const val KEY_TRACK_STATS = "sponsorblock_track_stats"
    const val KEY_SERVER = "sponsorblock_server"
    const val KEY_CATEGORY_MODE_PREFIX = "sponsorblock_category_mode_"
    const val KEY_CATEGORY_COLOR_PREFIX = "sponsorblock_category_color_"
    const val KEY_BLOCK_LIMIT = "sponsorblock_block_limit"
    const val KEY_USER_ID = "sponsorblock_user_id"
    const val KEY_USER_INFO = "sponsorblock_user_info"
    const val KEY_SKIP_COUNT = "sponsorblock_skip_count"
    const val KEY_SAVED_SECONDS = "sponsorblock_saved_seconds"
    const val KEY_LAST_STATUS = "sponsorblock_last_status"

    const val DEFAULT_SERVER = "https://bsbsb.top"
    const val DEFAULT_BLOCK_LIMIT = 0.0f

    val enabled: Boolean
        get() = ePrefs.getBoolean(KEY_ENABLED, false)

    val showToast: Boolean
        get() = ePrefs.getBoolean(KEY_SHOW_TOAST, true)

    val showProgress: Boolean
        get() = ePrefs.getBoolean(KEY_SHOW_PROGRESS, true)

    val trackStats: Boolean
        get() = ePrefs.getBoolean(KEY_TRACK_STATS, false)

    val blockLimit: Double
        get() = ePrefs.getFloat(KEY_BLOCK_LIMIT, DEFAULT_BLOCK_LIMIT).toDouble()

    val userId: String
        get() {
            val saved = ePrefs.getString(KEY_USER_ID, null)
                ?.takeIf { it.length >= 30 && it.all(Char::isLetterOrDigit) }
            if (saved != null) return saved
            val generated = generateUserId()
            setUserId(generated)
            return generated
        }

    val userInfo: String
        get() = ePrefs.getString(KEY_USER_INFO, "未获取") ?: "未获取"

    val server: String
        get() = ePrefs.getString(KEY_SERVER, DEFAULT_SERVER)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: DEFAULT_SERVER

    val status: String
        get() = ePrefs.getString(KEY_LAST_STATUS, "未检测") ?: "未检测"

    val requestCategories: Set<String>
        get() = SponsorBlockCategory.all
            .filter { modeOf(it.id) != SponsorBlockCategory.SkipMode.Disabled }
            .mapTo(linkedSetOf()) { it.id }

    val autoSkipCategories: Set<String>
        get() = SponsorBlockCategory.all
            .filter {
                val mode = modeOf(it.id)
                mode == SponsorBlockCategory.SkipMode.Always || mode == SponsorBlockCategory.SkipMode.Once
            }
            .mapTo(linkedSetOf()) { it.id }

    fun modeOf(category: String): SponsorBlockCategory.SkipMode {
        val defaultMode = SponsorBlockCategory.defaultModes[category] ?: SponsorBlockCategory.SkipMode.Disabled
        return SponsorBlockCategory.modeOf(ePrefs.getString(KEY_CATEGORY_MODE_PREFIX + category, defaultMode.value))
            ?: defaultMode
    }

    fun setMode(category: String, mode: SponsorBlockCategory.SkipMode) {
        ePrefs.edit().putString(KEY_CATEGORY_MODE_PREFIX + category, mode.value).commit()
    }

    fun colorOf(category: String): Int {
        val defaultColor = SponsorBlockCategory.defaultColors[category] ?: 0xFF00D400.toInt()
        return ePrefs.getInt(KEY_CATEGORY_COLOR_PREFIX + category, defaultColor)
    }

    fun setColor(category: String, color: Int) {
        ePrefs.edit().putInt(KEY_CATEGORY_COLOR_PREFIX + category, color).commit()
    }

    fun resetColor(category: String) {
        ePrefs.edit().remove(KEY_CATEGORY_COLOR_PREFIX + category).commit()
    }

    fun setShowToast(value: Boolean) {
        ePrefs.edit().putBoolean(KEY_SHOW_TOAST, value).commit()
    }

    fun setShowProgress(value: Boolean) {
        ePrefs.edit().putBoolean(KEY_SHOW_PROGRESS, value).commit()
    }

    fun setTrackStats(value: Boolean) {
        ePrefs.edit().putBoolean(KEY_TRACK_STATS, value).commit()
    }

    fun setBlockLimit(value: Double) {
        ePrefs.edit().putFloat(KEY_BLOCK_LIMIT, value.toFloat().coerceAtLeast(0f)).commit()
    }

    fun setUserId(value: String) {
        ePrefs.edit().putString(KEY_USER_ID, value).commit()
    }

    fun resetUserId() {
        setUserId(generateUserId())
    }

    fun setServer(value: String) {
        ePrefs.edit().putString(KEY_SERVER, value).commit()
    }

    fun addStats(savedSeconds: Long) {
        ePrefs.edit()
            .putInt(KEY_SKIP_COUNT, ePrefs.getInt(KEY_SKIP_COUNT, 0) + 1)
            .putLong(KEY_SAVED_SECONDS, ePrefs.getLong(KEY_SAVED_SECONDS, 0L) + savedSeconds.coerceAtLeast(0L))
            .apply()
    }

    fun setStatus(status: String) {
        ePrefs.edit().putString(KEY_LAST_STATUS, status).apply()
    }

    fun setUserInfo(info: String) {
        ePrefs.edit().putString(KEY_USER_INFO, info).apply()
    }

    private fun generateUserId(): String {
        val bytes = UUID.randomUUID().toString().toByteArray()
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
