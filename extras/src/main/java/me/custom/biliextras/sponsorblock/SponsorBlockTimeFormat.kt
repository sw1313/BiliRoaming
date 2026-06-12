package me.custom.biliextras.sponsorblock

import kotlin.math.max

object SponsorBlockTimeFormat {
    /** 格式化为 时:分:秒 或 分:秒（含可选毫秒）。 */
    fun formatMs(ms: Long): String {
        val safe = max(0L, ms)
        val totalSec = safe / 1000
        val fracMs = (safe % 1000).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val base = when {
            h > 0 -> "%d:%02d:%02d".format(h, m, s)
            m > 0 -> "%d:%02d".format(m, s)
            else -> "${s}s"
        }
        return if (fracMs > 0) "$base.${fracMs.toString().padStart(3, '0').trimEnd('0')}" else base
    }

    /** 解析 时:分:秒 / 分:秒 / 秒，支持小数毫秒。 */
    fun parseToMs(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(":")
        return when (parts.size) {
            1 -> parseSecPart(parts[0]) ?: trimmed.toDoubleOrNull()?.let { (it * 1000).toLong() }
            2 -> parseMinSec(parts[0], parts[1])
            3 -> parseHms(parts[0], parts[1], parts[2])
            else -> null
        }
    }

    fun parseToSec(text: String): Double? {
        val ms = parseToMs(text) ?: return null
        return ms / 1000.0
    }

    private fun parseHms(h: String, m: String, s: String): Long? {
        val hi = h.toIntOrNull() ?: return null
        val mi = m.toIntOrNull() ?: return null
        val secMs = parseSecPart(s) ?: return null
        return hi * 3_600_000L + mi * 60_000L + secMs
    }

    private fun parseMinSec(m: String, s: String): Long? {
        val mi = m.toIntOrNull() ?: return null
        val secMs = parseSecPart(s) ?: return null
        return mi * 60_000L + secMs
    }

    private fun parseSecPart(s: String): Long? {
        val dot = s.indexOf('.')
        return if (dot >= 0) {
            val whole = s.substring(0, dot).toIntOrNull() ?: return null
            val frac = s.substring(dot + 1)
            val fracMs = when (frac.length) {
                0 -> 0
                1 -> (frac.toIntOrNull() ?: return null) * 100
                2 -> (frac.toIntOrNull() ?: return null) * 10
                else -> (frac.take(3).padEnd(3, '0').toIntOrNull() ?: return null)
            }
            whole * 1000L + fracMs
        } else {
            s.toIntOrNull()?.times(1000L)
        }
    }
}
