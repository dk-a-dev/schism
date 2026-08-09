package ai.schism.split.sms.receipt

import ai.schism.split.ocr.api.OcrLine
import ai.schism.split.ocr.api.OcrPoint
import ai.schism.split.sms.receipt.engine.parseBill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the reported "receipt scan returns almost nothing": a thermal restaurant bill
 * photographed slightly tilted in a hand. The fixture is the SHIPPED OCR engine's own output for
 * that photo (quadrilaterals included) — see the file header — so this exercises the real boundary
 * between OCR and the parser, not an idealised hand-written row dump.
 *
 * The failure it locks down: [ocrLinesToRows] grouped detections into visual rows by absolute y, so
 * the page tilt slid the right-hand amount column a whole line down relative to the left-hand item
 * column. Every name/qty/amount pairing shifted by one, the "Total Invoice Value" label separated
 * from its amount, and the draft came out with total 0 and garbage items.
 */
class TiltedThermalBillTest {

    private fun fixtureLines(): List<OcrLine> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/receipt-engine/tilted-thermal-bill.txt")) {
            "missing tilted thermal bill fixture"
        }
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
                val (text, quad) = line.split('|')
                OcrLine(
                    text = text,
                    confidence = 1f,
                    points = quad.split(';').map { point ->
                        val (x, y) = point.split(',')
                        OcrPoint(x.toFloat(), y.toFloat())
                    },
                )
            }.toList()
        }
    }

    @Test
    fun tiltedThermalBillReadsEveryLineAndVerifies() {
        val draft = requireNotNull(parseBill(ocrLinesToRows(fixtureLines()))) { "parser returned null" }

        assertEquals("The Pizza Bakery, Church St", draft.merchant)
        assertEquals("2026-06-08", draft.date)

        // The 13 printed rows, in order, each with ITS OWN qty and amount — this is what the row
        // shear destroyed (amounts slid onto the following item, one line was dropped entirely).
        assertEquals(
            listOf(1, 2, 1, 1, 1, 1, 2, 1, 2, 1, 1, 1, 1),
            draft.lineItems.map { it.qty },
        )
        assertEquals(
            listOf(38800L, 85000L, 24800L, 32800L, 49800L, 38800L, 99600L, 34800L, 49600L, 44800L, 19800L, 32800L, 24200L),
            draft.lineItems.map { it.amountMinor },
        )
        assertEquals("GB Pesto SDT", draft.lineItems.first().name)
        // Items 2-3 carry the bill's one wrapped name ("8inch Chilli" / "pomodoro"): the moneyless
        // fragment sits one line pitch from BOTH neighbouring priced rows, so which item it joins is
        // the engine's documented tie convention, not something geometry can decide. The rest of the
        // names must come through verbatim.
        assertEquals(
            listOf(
                "Garlic Bread SDT", "10inch Margherita", "Truffle Fries", "Pepperoni Classic",
                "Tiramisu", "Cold Coffee", "Mushroom Risotto", "Fresh Lime Soda",
                "Choco Lava Cake", "Mineral Water",
            ),
            draft.lineItems.drop(3).map { it.name },
        )
        assertTrue(draft.lineItems[1].name.startsWith("8inch Chilli"))

        // Sub Total 5756.00; GST 287.80 (the combined row wins over the CGST/SGST split); round off
        // 0.20; "Total Invoice Value 6044" is the grand total.
        assertEquals(575600L, draft.subtotalMinor)
        assertEquals(28800L, draft.taxMinor)
        assertEquals(604400L, draft.totalMinor)
        assertTrue("items + tax + round off must reconcile to the printed total", draft.verified)
    }
}
