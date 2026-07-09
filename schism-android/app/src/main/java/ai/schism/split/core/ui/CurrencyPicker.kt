@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.schism.split.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A single supported currency: its symbol, ISO 4217 code, and display name. */
data class CurrencyOption(val symbol: String, val code: String, val name: String)

/** Common currencies offered by the default-currency pickers (Settings, Create group). */
val CURRENCIES: List<CurrencyOption> = listOf(
    CurrencyOption("₹", "INR", "Indian Rupee"),
    CurrencyOption("$", "USD", "US Dollar"),
    CurrencyOption("€", "EUR", "Euro"),
    CurrencyOption("£", "GBP", "British Pound"),
    CurrencyOption("¥", "JPY", "Japanese Yen"),
    CurrencyOption("$", "CAD", "Canadian Dollar"),
    CurrencyOption("$", "AUD", "Australian Dollar"),
    CurrencyOption("₩", "KRW", "South Korean Won"),
    CurrencyOption("AED", "AED", "UAE Dirham"),
    CurrencyOption("R$", "BRL", "Brazilian Real"),
)

private fun CurrencyOption.display() = "$symbol · $code — $name"

/**
 * Read-only dropdown for picking a currency (symbol + ISO code) from [CURRENCIES]. If the current
 * (symbol, code) pair isn't one of the known options, it's still shown as the field's value.
 */
@Composable
fun CurrencyPicker(
    symbol: String,
    code: String,
    onPick: (symbol: String, code: String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Currency",
) {
    var expanded by remember { mutableStateOf(false) }
    val current = CURRENCIES.find { it.symbol == symbol && it.code == code }
    val displayValue = current?.display() ?: listOf(symbol, code).filter { it.isNotBlank() }.joinToString(" · ")

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            CURRENCIES.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(option.symbol, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${option.code} — ${option.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onPick(option.symbol, option.code)
                        expanded = false
                    },
                )
            }
        }
    }
}
