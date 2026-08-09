package ai.schism.split.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PlusExportTest {

    private val utc = ZoneId.of("UTC")

    private val aug9 = ZonedDateTime.of(2026, 8, 9, 12, 0, 0, 0, utc).toInstant().toEpochMilli()

    @Test
    fun `csv has a stable header and one line per row`() {
        val csv = toCsv(listOf(ExportRow(aug9, "Cafe", 12345, "INR")), utc)
        assertEquals(
            "Date,Merchant,Amount,Currency\r\n2026-08-09,Cafe,123.45,INR\r\n",
            csv,
        )
    }

    @Test
    fun `commas quotes and newlines are escaped per RFC 4180`() {
        val csv = toCsv(listOf(ExportRow(aug9, "Bob\"s, Diner\nUptown", 100, "INR")), utc)
        assertTrue(csv.contains("\"Bob\"\"s, Diner\nUptown\""))
    }

    @Test
    fun `non-ascii merchants survive as UTF-8`() {
        val csv = toCsv(listOf(ExportRow(aug9, "Café Münster 東京", 100, "JPY")), utc)
        assertTrue(csv.contains("Café Münster 東京"))
        assertEquals(csv, String(csv.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `amounts keep two decimals and their sign`() {
        assertEquals("0.05", amountString(5))
        assertEquals("1.00", amountString(100))
        assertEquals("-42.07", amountString(-4207))
        assertEquals("12345.60", amountString(1234560))
    }

    @Test
    fun `exports carry no ids, tokens or debug fields`() {
        val csv = toCsv(listOf(ExportRow(aug9, "Cafe", 100, "INR")), utc)
        listOf("token", "userId", "Bearer", "id=", "remoteExpenseId").forEach {
            assertFalse(csv.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `pdf pagination fills whole pages and always yields at least one`() {
        assertEquals(1, paginate(emptyList()).size)
        val rows = List(ROWS_PER_PAGE + 1) { ExportRow(aug9, "M$it", 100, "INR") }
        val pages = paginate(rows)
        assertEquals(2, pages.size)
        assertEquals(ROWS_PER_PAGE, pages[0].size)
        assertEquals(1, pages[1].size)
        assertEquals(rows, pages.flatten())
    }
}
