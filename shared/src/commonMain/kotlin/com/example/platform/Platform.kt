package com.example.platform

/** Current time in milliseconds since the epoch. */
expect fun currentTimeMillis(): Long

/** Formats [millis] with a date pattern such as "MMM d, HH:mm", in the device's language. */
expect fun formatDateTime(millis: Long, pattern: String): String

/** Short time in the device's style, e.g. "2:30 PM" or "14:30". */
expect fun formatShortTime(millis: Long): String

/** Medium date in the device's style, e.g. "Sep 27, 2026". */
expect fun formatMediumDate(millis: Long): String

/** True when [millis] falls on today's date. */
expect fun isToday(millis: Long): Boolean

/** A random unique id. */
expect fun randomId(): String

/** Formats a number with [decimals] digits after the point, e.g. 18.855 -> "18.86". */
fun Double.formatDecimals(decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val negative = this < 0
    val scaled = kotlin.math.round(kotlin.math.abs(this) * factor).toLong()
    val whole = scaled / factor
    val fraction = (scaled % factor).toString().padStart(decimals, '0')
    return (if (negative && scaled != 0L) "-" else "") + if (decimals > 0) "$whole.$fraction" else "$whole"
}

/** Two-digit zero padding, e.g. 5 -> "05". */
fun Number.twoDigits(): String = toString().padStart(2, '0')

/** Form URL-encoding (UTF-8, space as '+'), the same as java.net.URLEncoder. */
fun urlEncode(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val c = byte.toInt().toChar()
        when {
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '*' || c == '_' -> append(c)
            c == ' ' -> append('+')
            else -> append('%').append(((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1).uppercase())
        }
    }
}

/** Reverses [urlEncode] (also accepts java.net.URLEncoder output). */
fun urlDecode(value: String): String {
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c == '+' -> { bytes.add(' '.code.toByte()); i++ }
            c == '%' && i + 2 <= value.lastIndex -> {
                bytes.add(value.substring(i + 1, i + 3).toInt(16).toByte()); i += 3
            }
            else -> { c.toString().encodeToByteArray().forEach { bytes.add(it) }; i++ }
        }
    }
    return bytes.toByteArray().decodeToString()
}

/** "Just now", "5 min ago", "3 hours ago", or the time today / the date for older ones. */
fun relativeTime(millis: Long): String {
    val diff = currentTimeMillis() - millis
    val minute = 60_000L
    return when {
        diff < minute -> "Just now"
        diff < 60 * minute -> "${diff / minute} min ago"
        isToday(millis) -> formatShortTime(millis)
        else -> formatMediumDate(millis)
    }
}
