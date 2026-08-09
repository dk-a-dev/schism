package ai.schism.split.sms.receipt.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Deterministic, text-and-geometry-only release battery. The corpus is synthetic: no image, OCR
 * engine, network, model, or private receipt is involved. Each TSV row contains both the synthetic
 * bill recipe and its explicit expected draft.
 */
class ReceiptEvaluationBatteryTest {
    private data class ExpectedItem(
        val name: String,
        val qty: Int,
        val unitMinor: Long,
        val amountMinor: Long,
    )

    private data class Charge(val label: String, val amountMinor: Long)

    private data class Fixture(
        val id: String,
        val category: String,
        val tags: Set<String>,
        val style: String,
        val currency: String,
        val currencyToken: String,
        val merchant: String,
        val date: String?,
        val numberStyle: String,
        val noise: String,
        val items: List<ExpectedItem>,
        val expectedSubtotal: Long,
        val taxes: List<Charge>,
        val fees: List<Charge>,
        val discountMinor: Long,
        val roundoffMinor: Long,
        val expectedTotal: Long,
        val expectedVerified: Boolean,
    )

    @Test
    fun exactly100SyntheticBillsMatchExplicitExpectedDrafts() {
        val fixtures = loadFixtures()
        assertEquals("release battery size", 100, fixtures.size)
        assertEquals("fixture IDs must be unique", 100, fixtures.map { it.id }.toSet().size)
        assertEquals(
            "ten independently countable scenarios are required per category",
            setOf(
                "currency", "decimals", "tax", "discount", "fees", "quantity",
                "malformed-ocr", "restaurant", "retail", "mixed",
            ).associateWith { 10 },
            fixtures.groupingBy { it.category }.eachCount(),
        )
        val requiredTags = setOf(
            "currency", "decimals", "tax", "discount", "fees", "quantity",
            "malformed-ocr", "restaurant", "retail",
        )
        assertTrue("coverage tags missing: ${requiredTags - fixtures.flatMap { it.tags }.toSet()}",
            fixtures.flatMap { it.tags }.toSet().containsAll(requiredTags))

        val failures = fixtures.mapNotNull { fixture ->
            runCatching { assertFixture(fixture) }.exceptionOrNull()?.let { error ->
                "${fixture.id}: ${error.message}"
            }
        }
        assertTrue("${100 - failures.size}/100 passed\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun assertFixture(fixture: Fixture) {
        val draft = parseBill(render(fixture)) ?: error("parser returned null")
        assertEquals("merchant", fixture.merchant, draft.merchant)
        assertEquals("currency", fixture.currency, draft.currency)
        assertEquals("date", fixture.date, draft.date)
        assertEquals("item names", fixture.items.map { it.name }, draft.lineItems.map { it.name })
        assertEquals("item quantities", fixture.items.map { it.qty }, draft.lineItems.map { it.qty })
        assertEquals("item unit prices", fixture.items.map { it.unitMinor }, draft.lineItems.map { it.unitPriceMinor })
        assertEquals("item amounts", fixture.items.map { it.amountMinor }, draft.lineItems.map { it.amountMinor })
        assertEquals("subtotal", fixture.expectedSubtotal, draft.subtotalMinor)
        assertEquals("fees", fixture.fees.sumOf { it.amountMinor }, draft.feesMinor)
        assertEquals("discount", fixture.discountMinor, draft.discountMinor)
        val expectedChargePot = fixture.taxes.sumOf { it.amountMinor } +
            fixture.fees.sumOf { it.amountMinor } - fixture.discountMinor + fixture.roundoffMinor
        assertEquals("tax/charge pot", expectedChargePot, draft.taxMinor)
        assertEquals("total", fixture.expectedTotal, draft.totalMinor)
        assertEquals("verification", fixture.expectedVerified, draft.verified)
    }

    private fun loadFixtures(): List<Fixture> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/receipt-engine/synthetic-receipt-battery.tsv")) {
            "missing synthetic receipt battery"
        }
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
                val p = line.split('\t')
                require(p.size == 18) { "expected 18 TSV columns, got ${p.size}: $line" }
                Fixture(
                    id = p[0], category = p[1], tags = p[2].split(',').filter(String::isNotBlank).toSet(),
                    style = p[3], currency = p[4], currencyToken = p[5], merchant = p[6],
                    date = p[7].takeUnless { it == "-" }, numberStyle = p[8], noise = p[9],
                    items = parseItems(p[10]), expectedSubtotal = p[11].toLong(),
                    taxes = parseCharges(p[12]), fees = parseCharges(p[13]),
                    discountMinor = p[14].toLong(), roundoffMinor = p[15].toLong(),
                    expectedTotal = p[16].toLong(), expectedVerified = p[17].toBooleanStrict(),
                )
            }.toList()
        }
    }

    private fun parseItems(raw: String): List<ExpectedItem> = raw.split(';').map { encoded ->
        val p = encoded.split('~')
        require(p.size == 4) { "bad item: $encoded" }
        ExpectedItem(p[0], p[1].toInt(), p[2].toLong(), p[3].toLong())
    }

    private fun parseCharges(raw: String): List<Charge> = raw.takeUnless { it == "-" }?.split(';')?.map { encoded ->
        val p = encoded.split('~')
        require(p.size == 2) { "bad charge: $encoded" }
        Charge(p[0], p[1].toLong())
    }.orEmpty()

    private fun render(f: Fixture): List<Row> {
        val rows = mutableListOf<Row>()
        var y = 20
        fun row(vararg cells: Triple<String, Int, Int>) {
            rows += Row(cells.map { (text, left, right) -> Cell(text, left, right, y) })
            y += 40
        }

        row(Triple(f.merchant, 40, 330))
        f.date?.let { row(Triple("Date: $it", 40, 240)) }
        if (f.noise == "PREAMBLE") row(Triple("GSTIN: [SYNTHETIC-REDACTED]", 40, 330))

        when (f.style) {
            "FOUR" -> row(
                Triple("Item", 40, 240), Triple("Qty", 300, 340),
                Triple("Rate", 380, 450), Triple("Amount", 500, 590),
            )
            "TWO" -> row(Triple("Description", 40, 260), Triple("Amount", 500, 590))
            "QTY_FIRST" -> row(
                Triple("Qty", 30, 70), Triple("Item", 110, 300),
                Triple("Rate", 380, 450), Triple("Amount", 500, 590),
            )
            "RATE_QTY" -> row(
                Triple("Particular", 40, 240), Triple("Price", 300, 370),
                Triple("Quantity", 410, 455), Triple("Amt", 500, 590),
            )
            "HEADERLESS" -> Unit
            else -> error("unknown style ${f.style}")
        }

        f.items.forEachIndexed { index, item ->
            if (index == 1 && f.noise == "GARBLE") row(Triple("### ???", 60, 180))
            if (index == 1 && f.noise == "PHONE") {
                row(Triple("Call", 40, 180), Triple("9999999999", 500, 590))
            }
            val unit = money(item.unitMinor, f.numberStyle)
            val amount = money(item.amountMinor, f.numberStyle)
            val qty = when (f.noise) {
                "QTY_X" -> "x${item.qty}"
                "QTY_NOS" -> "${item.qty} Nos"
                "QTY_DECIMAL" -> "${item.qty}.000"
                else -> item.qty.toString()
            }
            val nameParts = item.name.split(' ')
            val renderedName = if (f.noise == "WRAP" && index == 0 && nameParts.size > 1) nameParts.last() else item.name
            if (f.noise == "WRAP" && index == 0 && nameParts.size > 1) {
                row(Triple(nameParts.dropLast(1).joinToString(" "), 40, 260))
            }
            when (f.style) {
                "TWO" -> row(Triple(renderedName, 40, 260), Triple(amount, 500, 590))
                "QTY_FIRST" -> row(
                    Triple(qty, 30, 70), Triple(renderedName, 110, 300),
                    Triple(unit, 380, 450), Triple(amount, 500, 590),
                )
                "RATE_QTY" -> row(
                    Triple(renderedName, 40, 240), Triple(unit, 300, 370),
                    Triple(qty, 410, 455), Triple(amount, 500, 590),
                )
                else -> row(
                    Triple(renderedName, 40, 260), Triple(qty, 300, 340),
                    Triple(unit, 380, 450), Triple(amount, 500, 590),
                )
            }
        }

        row(Triple("Sub Total", 300, 450), Triple(decimalMoney(f.expectedSubtotal), 500, 590))
        f.taxes.forEach { row(Triple(it.label, 40, 300), Triple(decimalMoney(it.amountMinor), 500, 590)) }
        f.fees.forEach { row(Triple(it.label, 40, 300), Triple(decimalMoney(it.amountMinor), 500, 590)) }
        if (f.discountMinor != 0L) {
            row(Triple("Discount", 40, 300), Triple("-${decimalMoney(f.discountMinor)}", 500, 590))
        }
        if (f.roundoffMinor != 0L) {
            row(Triple("Round Off", 40, 300), Triple(signedMoney(f.roundoffMinor), 500, 590))
        }
        val totalLabel = when (f.id.takeLast(2).toInt() % 4) {
            0 -> "Amount Payable"
            1 -> "Grand Total"
            2 -> "Bill Amount"
            else -> "Total"
        }
        row(Triple(totalLabel, 40, 300), Triple("${f.currencyToken}${decimalMoney(f.expectedTotal)}", 500, 590))
        if (f.noise == "FOOTER") row(Triple("THANK YOU -- SYNTHETIC FIXTURE", 40, 360))
        return rows
    }

    private fun money(minor: Long, style: String): String = when (style) {
        "DOT" -> decimalMoney(minor)
        "COMMA" -> "%,.2f".format(java.util.Locale.US, minor / 100.0)
        "MID_DOT" -> decimalMoney(minor).replace('.', '·')
        "SLASH" -> "${minor / 100}/-"
        "BARE" -> (minor / 100).toString()
        "DROPPED" -> minor.toString()
        else -> error("unknown number style $style")
    }

    private fun decimalMoney(minor: Long): String = BigDecimal.valueOf(minor, 2).toPlainString()
    private fun signedMoney(minor: Long): String =
        if (minor < 0) "-${decimalMoney(-minor)}" else decimalMoney(minor)
}
