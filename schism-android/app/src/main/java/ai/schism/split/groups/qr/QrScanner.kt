package ai.schism.split.groups.qr

import android.content.Context
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Launch Google's on-device Code Scanner. It provides its own camera UI and requires no camera
 * permission (the scan runs in a Play-services process). [onResult] receives the scanned raw value,
 * or `null` if the scan failed or the user cancelled.
 */
fun scanQrCode(context: Context, onResult: (String?) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    GmsBarcodeScanning.getClient(context, options)
        .startScan()
        .addOnSuccessListener { onResult(it.rawValue) }
        .addOnFailureListener { onResult(null) }
        .addOnCanceledListener { onResult(null) }
}
