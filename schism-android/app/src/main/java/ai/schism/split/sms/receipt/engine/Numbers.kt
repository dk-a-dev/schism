package ai.schism.split.sms.receipt.engine

import kotlin.math.roundToLong

/** Regex for a plain (currency-stripped) numeric token: optional sign, digits, optional decimal. */
private val PLAIN_NUMBER = Regex("-?\\d+(\\.\\d+)?")

/**
 * Strips currency symbols, thousands separators and whitespace from [raw], leaving only the
 * characters that make up a plain signed decimal number.
 */
private fun cleanNumeric(raw: String): String =
    raw.trim().filter { it.isDigit() || it == '.' || it == '-' }

/**
 * Parses a money-shaped token (grouped digits with an optional 2-decimal fraction, e.g. "2,532",
 * "1,299.00", "₹40.00") into minor units (paise/cents), or `null` when [raw] doesn't look like an
 * amount at all, or looks more like a non-money number than a bill amount.
 *
 * A token with no decimal point whose integer part has 6 or more digits (e.g. a 10-digit phone
 * number) is rejected: real bill amounts of that magnitude are written with a decimal fraction,
 * so a bare 6+ digit integer is far more likely to be a phone number, order ID, or similar.
 */
fun parseMinor(raw: String): Long? {
    val cleaned = cleanNumeric(raw)
    if (cleaned.isEmpty() || !cleaned.matches(PLAIN_NUMBER)) return null

    val dotIndex = cleaned.indexOf('.')
    val hasDecimal = dotIndex >= 0
    val integerDigitCount = (if (hasDecimal) cleaned.substring(0, dotIndex) else cleaned).count { it.isDigit() }
    if (!hasDecimal && integerDigitCount >= 6) return null

    val value = cleaned.toDoubleOrNull() ?: return null
    return (value * 100).roundToLong()
}

/** True when [raw] parses as a money amount (see [parseMinor]). */
fun isMoneyToken(raw: String): Boolean = parseMinor(raw) != null

/** True when [raw] is a bare 1–3 digit integer in 1..999 — a plausible quantity, not an amount. */
fun isSmallInt(raw: String): Boolean {
    val cleaned = raw.trim()
    if (!cleaned.matches(Regex("\\d{1,3}"))) return false
    val value = cleaned.toIntOrNull() ?: return false
    return value in 1..999
}
