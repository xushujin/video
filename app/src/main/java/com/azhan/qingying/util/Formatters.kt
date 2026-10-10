package com.azhan.qingying.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 毫秒 → `1:23:45` / `12:34`。负数与未知（<=0）都返回 `--:--`。 */
fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** 毫秒 → `05:30`（倒计时用，固定两位分钟，最多到 99 分钟）。 */
fun formatCountdown(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

/** 字节 → `1.2 GB`。 */
fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "${bytes} B"
    else String.format(Locale.US, "%.1f %s", value, units[unit])
}

/** 时间戳 → `2026-10-10`。 */
fun formatDate(ms: Long): String {
    if (ms <= 0L) return "—"
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
}

/** 倍速 → `1.0x` / `1.25x`。整数倍速保留一位小数，非整数保留两位。 */
fun formatSpeed(speed: Float): String {
    val pattern = if (speed == speed.toInt().toFloat()) "%.1f" else "%.2f"
    return String.format(Locale.US, pattern, speed) + "x"
}
