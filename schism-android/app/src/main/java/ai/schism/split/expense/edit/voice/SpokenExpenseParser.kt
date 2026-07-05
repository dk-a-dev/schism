package ai.schism.split.expense.edit.voice

import ai.schism.split.groups.data.Participant

/**
 * A structured draft extracted from a spoken sentence like
 * "paid 800 for dinner, split with Riya and Sam", ready to prefill the expense form.
 * Money is Long minor units. Like the receipt parser this is the on-device "structure the
 * transcript" step: it uses heuristics (no LLM) so it stays fully offline and unit-testable.
 * Every field is nullable so a partial utterance still yields a usable draft; the caller keeps
 * whatever the parse left null.
 */
data class SpokenExpenseDraft(
    val title: String?,
    val amountMinor: Long?,
    val payerParticipantId: String?,
    val paidForParticipantIds: List<String>?,
    val isPersonal: Boolean,
)

// First number, tolerating grouping (1,200) and up to two decimals (800.50). Order matters: the
// grouped alternative comes first so "1,200" is captured whole rather than as a bare "1".
private val AMOUNT_REGEX = Regex("""\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?""")

// The speaker paid it themselves ("I paid", "I spent", "my dinner").
private val YOU_PAID_REGEX = Regex("""\bi\s+(?:paid|spent)\b|\bmy\b""")

// "<name> paid ...": the word right before "paid" names the payer.
private fun namePaidRegex(name: String) = Regex("""\b${Regex.escape(name)}\s+paid\b""")

// A split was described: everything after "with"/"split" lists who shares the cost.
private val SPLIT_REGEX = Regex("""\b(?:with|split)\b""")

// Solo expense: no split, just the speaker.
private val PERSONAL_REGEX = Regex("""\b(?:just me|only me|personal)\b""")

private val ME_REGEX = Regex("""\b(?:me|myself)\b""")

// Filler words we never want to leak into the derived title.
private val TITLE_STOP_WORDS = setOf(
    "i", "paid", "spent", "split", "with", "and", "for", "on", "the", "a", "an",
    "just", "only", "me", "myself", "my", "personal", "of", "to", "rs", "inr",
    "rupees", "dollars", "each", "between", "us",
)

/**
 * Parse a free-form spoken [text] into a [SpokenExpenseDraft]:
 * - **amount**: the first number in the sentence, to minor units (null if none).
 * - **payer**: "you" when the speaker paid ("I paid" / "my "); else the participant named right
 *   before "paid" ("Sam paid ..."); else "you".
 * - **paidFor**: the participants named after "with"/"split" (comma/and separated) plus the speaker,
 *   or just the speaker for "just me" / "only me" / "personal" (with [isPersonal] set); null when no
 *   split is described, so the caller keeps its current selection.
 * - **title**: the phrase after "for" ("...for dinner" -> "Dinner"), else the leftover words with the
 *   amount, names and keywords stripped; Title-cased; null if nothing is left.
 * Case-insensitive and robust to missing pieces (any field may be null). Pure: no Android deps.
 */
fun parseSpokenExpense(
    text: String,
    participants: List<Participant>,
    youParticipantId: String?,
): SpokenExpenseDraft {
    val lower = text.lowercase()

    val amountMatch = AMOUNT_REGEX.find(text)
    val amountMinor = amountMatch?.value?.let(::toMinor)

    val payer = when {
        YOU_PAID_REGEX.containsMatchIn(lower) -> youParticipantId
        else -> participants
            .firstOrNull { namePaidRegex(it.name.lowercase()).containsMatchIn(lower) }
            ?.id
            ?: youParticipantId
    }

    val personal = PERSONAL_REGEX.containsMatchIn(lower)
    val splitMatch = SPLIT_REGEX.find(lower)
    val paidFor: List<String>? = when {
        personal -> listOfNotNull(youParticipantId)
        splitMatch != null -> {
            val region = lower.substring(splitMatch.range.last + 1)
            val ids = LinkedHashSet<String>()
            // The speaker is part of any split they describe.
            youParticipantId?.let(ids::add)
            if (ME_REGEX.containsMatchIn(region)) youParticipantId?.let(ids::add)
            participants.forEach { p ->
                if (Regex("""\b${Regex.escape(p.name.lowercase())}\b""").containsMatchIn(region)) {
                    ids.add(p.id)
                }
            }
            ids.toList().ifEmpty { null }
        }
        else -> null
    }

    val title = extractTitle(lower, amountMatch?.value, participants)

    return SpokenExpenseDraft(
        title = title,
        amountMinor = amountMinor,
        payerParticipantId = payer,
        paidForParticipantIds = paidFor,
        isPersonal = personal,
    )
}

/** "800" / "1,200" / "800.50" -> minor units (80000 / 120000 / 80050), or null when not positive. */
private fun toMinor(raw: String): Long? {
    val value = raw.replace(",", "").toDoubleOrNull() ?: return null
    if (value <= 0.0) return null
    return Math.round(value * 100)
}

private fun extractTitle(
    lower: String,
    amountRaw: String?,
    participants: List<Participant>,
): String? {
    // Preferred: the phrase after "for", cut at the next structural keyword.
    Regex("""\bfor\s+(.+)""").find(lower)?.let { m ->
        var phrase = m.groupValues[1]
        for (stop in listOf(" with ", " split", " and ", " just", " only", " personal", ",")) {
            val i = phrase.indexOf(stop)
            if (i >= 0) phrase = phrase.substring(0, i)
        }
        titleCase(phrase).takeIf { it.isNotBlank() }?.let { return it }
    }

    // Fallback: whatever remains once the amount, names and filler words are removed.
    val nameWords = participants.flatMap { it.name.lowercase().split(Regex("\\s+")) }.toSet()
    val stripped = amountRaw?.let { lower.replace(it.lowercase(), " ") } ?: lower
    val leftover = stripped
        .split(Regex("[\\s,]+"))
        .filter { token -> token.any { it.isLetter() } }
        .filter { it !in TITLE_STOP_WORDS && it !in nameWords }
        .joinToString(" ")
    return titleCase(leftover).takeIf { it.isNotBlank() }
}

private fun titleCase(s: String): String =
    s.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
