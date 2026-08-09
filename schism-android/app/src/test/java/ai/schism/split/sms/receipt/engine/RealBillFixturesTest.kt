package ai.schism.split.sms.receipt.engine

import ai.schism.split.sms.receipt.ChargeKind
import ai.schism.split.sms.receipt.ReceiptDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four REAL restaurant bills, transcribed as the OCR reads them: one text line per detected line,
 * in reading order, with the printed column alignment preserved. A run of 2+ spaces is a column
 * gap (that is exactly how the shipped detector splits a printed line into separate detections —
 * see `tilted-thermal-bill.txt`), so the fixtures carry real geometry without hand-written pixels.
 *
 * Each bill exercises a layout family the generic engine got wrong:
 *  A — two-line item blocks: the name on one line, `qty @ rate/ea` + amount on the next.
 *  B — `N x Item` quantity prefix with names wrapping onto a following line.
 *  C — Item/Qty/Rate/Amount columns with indented SAC/CGST annotation sub-lines between items.
 *  D — US bill, no currency symbol anywhere, quantity in its own leading column.
 */
class RealBillFixturesTest {

    /** Maximal runs of non-space text (single spaces kept inside a cell); 2+ spaces = a column gap. */
    private val CELLS = Regex("""[^ ]+(?: [^ ]+)*""")

    /** One [Cell] per detected column run; x in tenths of a character cell, y one line pitch apart. */
    private fun rowsOf(resource: String): List<Row> {
        val text = requireNotNull(javaClass.getResourceAsStream("/receipt-engine/$resource")) {
            "missing fixture $resource"
        }.bufferedReader().readText()
        return text.lines().filter { it.isNotBlank() }.mapIndexed { line, raw ->
            Row(
                CELLS.findAll(raw).map { m ->
                    Cell(m.value, m.range.first * 10, m.range.last * 10, line * 40)
                }.toList(),
            )
        }
    }

    /** `label|kind|signedMinor` per charge row, plus the invariant that they fold back to the pot. */
    private fun charges(draft: ReceiptDraft): List<String> {
        assertEquals(
            "charge lines must sum to the charge pot",
            draft.taxMinor,
            draft.chargeLines.sumOf { it.amountMinor },
        )
        return draft.chargeLines.map { "${it.label}|${it.kind}|${it.amountMinor}" }
    }

    // ======================================================================================
    // Bill A — dotpe thermal: name line + `qty @ rate/ea` continuation line carrying the amount
    // ======================================================================================

    @Test fun dotpeTwoLineItemBlocks() {
        val draft = parseBill(rowsOf("real-bill-a-dotpe.txt"))!!

        // Known residual: this bill's outlet name ("FML PASHAN") shares a line with its timestamp,
        // so the first letters-dominant non-metadata row is the API reference above it.
        assertEquals("Source: API #3608098", draft.merchant)

        assertEquals(
            listOf(
                "CHICKEN BANJARA KEBAB", "DRUMS OF HEAVEN", "FRUIT PUNCH", "PACKAGED DRINKING WATER",
                "BUDWEISER MAGNUM TOWER", "RED BULL ENERGY DRINK", "RED BULL ENERGY DRINK",
                "RED BULL ENERGY DRINK", "BUTTER NAAN", "CHICKEN LABABDAR",
            ),
            draft.lineItems.map { it.name },
        )
        assertEquals(listOf(1, 2, 1, 2, 1, 1, 1, 1, 4, 1), draft.lineItems.map { it.qty })
        assertEquals(
            listOf(69900L, 109800L, 59900L, 8000L, 350000L, 32500L, 32500L, 32500L, 43600L, 69900L),
            draft.lineItems.map { it.amountMinor },
        )
        assertEquals(listOf(69900L, 54900L, 59900L, 4000L, 350000L, 32500L, 32500L, 32500L, 10900L, 69900L),
            draft.lineItems.map { it.unitPriceMinor })

        assertEquals(808600L, draft.subtotalMinor)
        // Service Charge 10% is a fee, never an item.
        assertEquals(80860L, draft.feesMinor)
        // charge pot = CGST 114.65 + SGST 114.65 + VAT 350.00 + service 808.60
        assertEquals(138790L, draft.taxMinor)
        // Five charge rows, individually correctable — not one opaque "Tax" line.
        assertEquals(
            listOf(
                "Service Charge 10%|${ChargeKind.FEE}|80860",
                "CGST2.5 2.5%|${ChargeKind.TAX}|11465",
                "SGST2.5 2.5%|${ChargeKind.TAX}|11465",
                "VAT 10%|${ChargeKind.TAX}|35000",
            ),
            charges(draft),
        )
        // "Bill Total (rounded)" is printed last and is the payable figure.
        assertEquals(947400L, draft.totalMinor)
        assertTrue(draft.verified)
        // The printed "(15 Qty)" header count.
        assertEquals(15, draft.lineItems.sumOf { it.qty })
    }

    // ======================================================================================
    // Bill B — Cedarstay: "N x Item" qty prefix, wrapped names, digital render
    // ======================================================================================

    @Test fun cedarstayQtyPrefixAndWrappedNames() {
        val draft = parseBill(rowsOf("real-bill-b-cedarstay.txt"))!!

        assertEquals("Cedarstay Hotels", draft.merchant)
        assertEquals("2026-02-23", draft.date)
        assertEquals("₹", draft.currency)
        assertEquals(
            listOf(
                "Hakka Noodles", "Hakka Noodles", "Veg Biryani",
                "Paneer Butter Masala", "Paneer Butter Masala", "Hakka Noodles",
            ),
            draft.lineItems.map { it.name },
        )
        assertEquals(listOf(5, 2, 2, 3, 2, 2), draft.lineItems.map { it.qty })
        assertEquals(
            listOf(40000L, 90000L, 56000L, 60000L, 52000L, 82000L),
            draft.lineItems.map { it.amountMinor },
        )
        assertEquals(380000L, draft.subtotalMinor)
        assertEquals(38000L, draft.taxMinor) // CGST 190 + SGST 190
        assertEquals(
            listOf("CGST (5%):|${ChargeKind.TAX}|19000", "SGST (5%):|${ChargeKind.TAX}|19000"),
            charges(draft),
        )
        assertEquals(418000L, draft.totalMinor)
        assertTrue(draft.verified)
    }

    // ======================================================================================
    // Bill C — faded thermal: SAC/CGST annotation sub-lines interleaved between item rows
    // ======================================================================================

    @Test fun thermalWithSacSublinesBetweenItems() {
        val draft = parseBill(rowsOf("real-bill-c-thermal-sac.txt"))!!

        // Known residual: this bill prints no merchant name at all (it opens on its GSTIN), so the
        // first letters-dominant non-metadata row is the guest line.
        assertEquals("Guest: CASH", draft.merchant)

        assertEquals(
            listOf("RED BULL/PERRIER", "DAL TADKA WALI", "JEERA RICE", "DIET COKE/PEPSI", "FINGER CHIPS"),
            draft.lineItems.map { it.name },
        )
        assertEquals(listOf(1, 1, 1, 1, 1), draft.lineItems.map { it.qty })
        assertEquals(
            listOf(22000L, 22000L, 16500L, 9000L, 18500L),
            draft.lineItems.map { it.amountMinor },
        )
        assertEquals(88000L, draft.subtotalMinor)
        assertEquals(8800L, draft.feesMinor) // Serv. Chg @ 10.00%
        // charge pot = CGST 87.12 + SGST 87.12 + service 88.00 − round off 0.24 (the "(-)" is
        // printed beside the label, not on the amount)
        assertEquals(26200L, draft.taxMinor)
        assertEquals(
            listOf(
                "Serv. Chg @ 10.00%|${ChargeKind.FEE}|8800",
                "CGST 9.00%|${ChargeKind.TAX}|8712",
                "SGST 9.00%|${ChargeKind.TAX}|8712",
                "Round off(-)|${ChargeKind.ROUNDOFF}|-24",
            ),
            charges(draft),
        )
        // "Net Amount" is the last printed total and the payable one — not the larger "Total Amount".
        assertEquals(114200L, draft.totalMinor)
        assertTrue(draft.verified)
    }

    // ======================================================================================
    // Bill D — US bill with no currency symbol printed anywhere
    // ======================================================================================

    @Test fun usBillWithNoCurrencySymbol() {
        val draft = parseBill(rowsOf("real-bill-d-us-no-symbol.txt"))!!

        assertEquals("Chinese Plate", draft.merchant)
        // Nothing on the bill names a currency, so none is reported — inventing the rupee here put a
        // ₹ on a US receipt. Display falls back to a bare amount.
        assertEquals("", draft.currency)
        assertEquals(listOf("Chinese Buffet", "Soda", "Desserts"), draft.lineItems.map { it.name })
        assertEquals(listOf(4, 4, 4), draft.lineItems.map { it.qty })
        assertEquals(listOf(5196L, 796L, 1556L), draft.lineItems.map { it.amountMinor })
        assertEquals(7548L, draft.subtotalMinor)
        assertEquals(418L, draft.taxMinor) // Food Tax 2.90 + Local Tax 1.28
        assertEquals(
            listOf("Food Tax|${ChargeKind.TAX}|290", "Local Tax|${ChargeKind.TAX}|128"),
            charges(draft),
        )
        assertEquals(7966L, draft.totalMinor)
        assertTrue(draft.verified)
    }
}
