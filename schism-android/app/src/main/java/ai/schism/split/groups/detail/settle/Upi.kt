package ai.schism.split.groups.detail.settle

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlin.math.abs

/**
 * Builds an implicit `upi://pay` intent for [amountMinor] (minor units) with [note] as the transaction
 * note. No payee VPA is supplied — the goal is a one-tap launch into the user's UPI app, which then asks
 * them to pick a payee. Amount is rendered as major units with two decimals (e.g. 50000 -> "500.00").
 */
fun upiPaymentIntent(amountMinor: Long, note: String): Intent {
    val uri = Uri.parse("upi://pay?am=${majorDecimal(amountMinor)}&cu=INR&tn=${Uri.encode(note)}")
    return Intent(Intent.ACTION_VIEW, uri)
}

/** Launches a UPI-app chooser for [amountMinor]. No-ops if the device has nothing that can handle it. */
fun launchUpi(context: Context, amountMinor: Long, note: String) {
    val chooser = Intent.createChooser(upiPaymentIntent(amountMinor, note), "Pay with UPI")
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        // No UPI-capable app installed — nothing to do.
    }
}

private fun majorDecimal(amountMinor: Long): String {
    val cents = abs(amountMinor)
    return "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}
