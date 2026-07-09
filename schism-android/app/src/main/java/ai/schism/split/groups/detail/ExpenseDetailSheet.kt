@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.schism.split.groups.detail

import ai.schism.split.core.money.formatMinor
import ai.schism.split.core.ui.SchismPrimaryButton
import ai.schism.split.expense.data.Expense
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Read-only breakdown of any expense: what it was, who paid, how it split per person, and — for a
 * receipt split by items — a clean itemised list of who had what. The creator also gets an Edit
 * button (the editor owns delete).
 */
@Composable
fun ExpenseDetailSheet(
    expense: Expense,
    participantNames: Map<String, String>,
    currency: String,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {

            // ── Header ─────────────────────────────────────────────
            Text(
                expense.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                formatMinor(expense.amount, currency) + "  ·  " + prettyDate(expense.expenseDate),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Paid by " + (participantNames[expense.paidById] ?: "someone"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Split between ──────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            SectionLabel("Split between")
            Spacer(Modifier.height(8.dp))
            expense.paidFor.forEach { pf ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        participantNames[pf.participantId] ?: pf.participantId,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        when (expense.splitMode) {
                            "BY_AMOUNT" -> formatMinor(pf.shares, currency)
                            "BY_PERCENTAGE" -> "${pf.shares / 100.0}%"
                            else -> "${pf.shares}×"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // ── Items (only for a receipt split by items) ─────────
            val items = parseItemBreakdown(expense.notes)
            val taxes = parseTaxBreakdown(expense.notes)
            val isStructuredNotes = expense.notes.startsWith("Split by items")
            if (expense.splitMode == "BY_AMOUNT" && items.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                SectionLabel("Items")
                Spacer(Modifier.height(8.dp))
                items.forEach { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                        if (item.sharedBy.isNotBlank()) {
                            Text(
                                item.sharedBy,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ── Taxes (labelled SGST/CGST/etc breakdown, if the notes carry one) ──
            if (taxes.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                SectionLabel("Taxes")
                Spacer(Modifier.height(8.dp))
                taxes.forEach { tax ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(tax.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            formatMinor(tax.amountMinor, currency),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            if (!isStructuredNotes && expense.notes.isNotBlank()) {
                Spacer(Modifier.height(24.dp))
                SectionLabel("Notes")
                Spacer(Modifier.height(8.dp))
                Text(expense.notes, style = MaterialTheme.typography.bodyMedium)
            }

            if (canEdit) {
                Spacer(Modifier.height(28.dp))
                SchismPrimaryButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    Text("Edit expense")
                }
            }
        }
    }
}

/** A quiet uppercase eyebrow label that heads each section — refined, not shouty. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
    )
}

private data class SplitItem(val name: String, val sharedBy: String)
private data class NoteTaxLine(val label: String, val amountMinor: Long)

/**
 * Parses the "Split by items:" note the itemised flow (and claim-session finalize) writes into an
 * expense into structured rows. Each source line looks like `• <item name> — <PersonA×2, PersonB>`;
 * the leading "Split by items:" caption is dropped (the section already labels it). Item lines stop
 * at a "Taxes:" line, if any (see [parseTaxBreakdown]), so a labelled tax breakdown never gets
 * mistaken for an item row. Returns empty when the note isn't a breakdown.
 */
private fun parseItemBreakdown(notes: String): List<SplitItem> {
    if (!notes.startsWith("Split by items")) return emptyList()
    return itemSectionLines(notes)
        .filter { it.startsWith("•") }
        .mapNotNull { line ->
            val body = line.removePrefix("•").trim()
            val dash = body.lastIndexOf(" — ")
            if (dash < 0) SplitItem(body, "") else SplitItem(body.substring(0, dash).trim(), body.substring(dash + 3).trim())
        }
}

/**
 * Parses the "Taxes:" section a claim-session finalize appends after the item breakdown — one
 * `• <label>: <amountMinor>` line per labelled tax (e.g. `• SGST 2.5%: 2360`), amounts kept as raw
 * minor-unit integers so the caller formats them with formatMinor. Returns empty when the note has no
 * "Taxes:" section.
 */
private fun parseTaxBreakdown(notes: String): List<NoteTaxLine> {
    if (!notes.startsWith("Split by items")) return emptyList()
    val lines = notes.lineSequence().map { it.trim() }.toList()
    val taxIdx = lines.indexOf("Taxes:")
    if (taxIdx < 0) return emptyList()
    return lines.drop(taxIdx + 1)
        .filter { it.startsWith("•") }
        .mapNotNull { line ->
            val body = line.removePrefix("•").trim()
            val colon = body.lastIndexOf(":")
            if (colon < 0) return@mapNotNull null
            val amount = body.substring(colon + 1).trim().toLongOrNull() ?: return@mapNotNull null
            NoteTaxLine(body.substring(0, colon).trim(), amount)
        }
}

/** The note's lines up to (but excluding) a "Taxes:" marker line, if any — the item-breakdown section. */
private fun itemSectionLines(notes: String): List<String> {
    val lines = notes.lineSequence().map { it.trim() }.toList()
    val taxIdx = lines.indexOf("Taxes:")
    return if (taxIdx < 0) lines else lines.subList(0, taxIdx)
}

/** "2026-07-09" → "9 Jul 2026"; falls back to the raw date-only string if it can't be parsed. */
private fun prettyDate(iso: String): String {
    val datePart = iso.take(10)
    return runCatching {
        LocalDate.parse(datePart).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
    }.getOrDefault(datePart)
}
