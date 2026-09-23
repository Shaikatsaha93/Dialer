package com.example.data.model

/** Balance reported by iTelSwitchPlus in the "iTelSwitchPlus" header of each REGISTER 200 OK. */
data class AccountBalance(
    val amount: Double,
    val currency: String
) {
    val formatted: String get() = "%.2f %s".format(amount, currency)

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
