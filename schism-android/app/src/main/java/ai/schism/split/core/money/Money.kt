package ai.schism.split.core.money

import kotlin.math.abs

/**
 * Formats [amount] in minor units (paise/cents) as a display string prefixed with the [currency]
 * symbol, e.g. `formatMinor(4200, "₹")` -> `"₹42.00"`. Money is Long minor units end-to-end; this is
 * the only place it becomes a string, and only for display.
 */
fun formatMinor(amount: Long, currency: String): String {
    val cents = abs(amount)
    val whole = groupThousands(cents / 100)
    val frac = (cents % 100).toInt().toString().padStart(2, '0')
    val sign = if (amount < 0) "-" else ""
    return "$sign$currency$whole.$frac"
}

private fun groupThousands(value: Long): String {
    val digits = value.toString()
    if (digits.length <= 3) return digits
    val head = digits.length % 3
    val groups = ArrayList<String>()
    if (head > 0) groups.add(digits.substring(0, head))
    var i = head
    while (i < digits.length) {
        groups.add(digits.substring(i, i + 3))
        i += 3
    }
    return groups.joinToString(",")
}
