package com.example.sip

/**
 * The user part to dial for what was typed. Bangladeshi numbers in international form
 * (+8801712345678, 008801712345678, 8801712345678) go out in the local form the switch
 * routes (01712345678): a mobile network understands "+880", but the SIP switch answered it
 * with an error while local numbers went through. Anything else (SIP usernames, extensions,
 * other countries) is sent unchanged.
 */
fun toDialableNumber(user: String): String {
    val digits = when {
        user.startsWith("+") -> user.drop(1)
        user.startsWith("00") -> user.drop(2)
        else -> user
    }
    if (digits.isEmpty() || !digits.all { it.isDigit() }) return user
    // 880 + national number without its leading 0 (mobile 1XXXXXXXXX, IP phone 9XXXXXXXXX, ...)
    if (digits.startsWith("880") && digits.length in 12..13) return "0" + digits.drop(3)
    return user
}
