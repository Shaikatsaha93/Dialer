package com.example.data.model

import com.example.platform.formatDecimals

/** Balance reported by iTelSwitchPlus in the "iTelSwitchPlus" header of each REGISTER 200 OK. */
data class AccountBalance(
    val amount: Double,
    val currency: String
) {
    val formatted: String get() = "${amount.formatDecimals(2)} $currency"

    companion object {
        /** Parses e.g. "Balance=18.855 Credit-Limit=0.0 Total-Limit=18.855 Currency=BDT status=1". */
        fun parse(header: String?): AccountBalance? {
            if (header.isNullOrBlank()) return null
            val amount = Regex("""Balance=(-?[\d.]+)""").find(header)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: return null
            val currency = Regex("""Currency=(\w+)""").find(header)?.groupValues?.get(1).orEmpty()
            return AccountBalance(amount, currency)
        }
    }
}
