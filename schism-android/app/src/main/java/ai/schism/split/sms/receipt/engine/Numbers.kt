package ai.schism.split.sms.receipt.engine

import kotlin.math.roundToLong

/** Regex for a plain (currency-stripped) numeric token: optional sign, digits, optional 1-2 digit decimal fraction (money shape). */
private val PLAIN_NUMBER = Regex("-?\\d+(\\.\\d{1,2})?")

/**
 * Strips currency symbols, thousands separators and whitespace from [raw], leaving only the
 * characters that make up a plain signed decimal number.
 *
 * A mid-dot decimal separator (`149·00`, common on some thermal printers) is normalized to a real
 * `.`. A minus sign is kept ONLY when it leads the token (a genuine negative like `-0.20`); a
 * trailing dash — as in the Indian rupee notation `149/-` ("₹149 flat") — is dropped, along with the
 * slash, so the token parses as the whole amount it denotes rather than failing to parse at all.
 */
/**
 * A number whose EVERY dot-separated group after the first is exactly 3 digits — "48.000",
 * "1.591.600". A money fraction is 1-2 digits, so this shape can only be thousands grouping, which
 * is how Indonesia, Germany, Spain, Brazil, Turkey and much of Europe print an amount. Commas are
 * already stripped as grouping unconditionally; this is the symmetric rule for the dot.
 *
 * ponytail: a genuine 3-decimal token (a fuel price like "3.499"/litre) reads as 3499 here. It read
 * as *nothing at all* before — the whole token was rejected — so a whole locale going from
 * unparseable to parseable is worth that edge. Tighten with a per-bill separator vote if it bites.
 */
private val DOT_GROUPED = Regex("""\d{1,3}(?:\.\d{3})+""")

private fun cleanNumeric(raw: String): String {
    val t = raw.trim().replace('·', '.')
    var digits = t.filter { it.isDigit() || it == '.' }
    if (DOT_GROUPED.matches(digits)) digits = digits.replace(".", "")
    return if (t.startsWith('-')) "-$digits" else digits
}

/**
 * True when [raw] carries a letter. Stripping is only ever meant to drop currency/grouping noise
 * (`₹`, `,`, `/-`), so anything with a letter in it is a LABEL that happens to contain digits — a
 * merchant name ("The Pizza Bakery 2"), a tax id ("GSTIN:29AAFCP1234M1ZK"), a reference
 * ("Bill No.: 3241"). Without this guard [cleanNumeric] deletes the letters and the leftover digits
 * parse as an amount, so those rows are mistaken for priced rows — and the merchant-name line, which
 * is skipped precisely because it "ends in a money cell", is never found at all.
 */
private fun hasLetter(raw: String): Boolean = raw.any { it.isLetter() }

/**
 * A per-unit suffix on a printed rate: `699/ea`, `40/pc`, `55/kg` — the Indian POS spelling of "₹699
 * each". Like the `149/-` notation [cleanNumeric] already understands, the letters are notation, not
 * a label, so they're stripped before [hasLetter] rejects the token. The whole token must be
 * `<number>/<short word>`, so a genuine label that merely contains a slash ("RED BULL/PERRIER") is
 * still rejected by [hasLetter].
 */
private val PER_UNIT_SUFFIX = Regex("""\s*/\s*[A-Za-z]{1,4}\.?$""")

/**
 * Parses a money-shaped token (grouped digits with an optional 2-decimal fraction, e.g. "2,532",
 * "1,299.00", "₹40.00") into minor units (paise/cents), or `null` when [raw] doesn't look like an
 * amount at all, or looks more like a non-money number than a bill amount.
 *
 * A token with no decimal point whose integer part has 8 or more digits (phone-number length) is
 * rejected: this app targets large group/catering bills, so a legit whole-rupee total can run into
 * 6-7 digits (e.g. "123456" = ₹1,23,456) without a decimal fraction. Only phone-number-length (or
 * longer) bare integers — 8+ digits — are far more likely to be a phone number, order ID, or
 * similar than a bill amount, so only those are rejected.
 *
 * A token carrying a percent sign (e.g. "2.5%", "18%") is a tax/discount RATE, never a money
 * amount, so it's rejected outright — otherwise `isMoneyToken("2.5%")` would be true and a
 * percentage column could be misread as an amount when detecting money columns / regions.
 *
 * A token carrying a colon is likewise never money: it's a clock time ("20:44", "18:34") or a
 * label/value pair. [cleanNumeric] deletes every character it doesn't recognise, so without this
 * guard "20:44" silently became ₹20.44 — enough to make a bill's preamble time row look like the
 * first priced item line and swallow the whole header into the item list.
 */
fun parseMinor(raw: String): Long? {
    if (raw.contains('%') || raw.contains(':')) return null
    val stripped = PER_UNIT_SUFFIX.replace(raw.trim(), "")
    if (hasLetter(stripped)) return null
    val cleaned = cleanNumeric(stripped)
    if (cleaned.isEmpty() || !cleaned.matches(PLAIN_NUMBER)) return null

    val dotIndex = cleaned.indexOf('.')
    val hasDecimal = dotIndex >= 0
    val integerDigitCount = (if (hasDecimal) cleaned.substring(0, dotIndex) else cleaned).count { it.isDigit() }
    if (!hasDecimal && integerDigitCount >= 8) return null

    val value = cleaned.toDoubleOrNull() ?: return null
    return (value * 100).roundToLong()
}

/** True when [raw] parses as a money amount (see [parseMinor]). */
fun isMoneyToken(raw: String): Boolean = parseMinor(raw) != null

/**
 * True when [raw] is a rate written with its per-unit suffix — "699/ea", "40/pc", "55/kg". The
 * suffix says outright that the number is a price PER unit, which is why such a token is never part
 * of an item name however far left of the amount column it happens to be printed.
 */
fun isPerUnitRate(raw: String): Boolean =
    PER_UNIT_SUFFIX.containsMatchIn(raw.trim()) && isMoneyToken(raw)

/** True when [raw] is a bare 1–3 digit integer in 1..999 — a plausible quantity, not an amount. */
fun isSmallInt(raw: String): Boolean {
    val cleaned = raw.trim()
    if (!cleaned.matches(Regex("\\d{1,3}"))) return false
    val value = cleaned.toIntOrNull() ?: return false
    return value in 1..999
}
