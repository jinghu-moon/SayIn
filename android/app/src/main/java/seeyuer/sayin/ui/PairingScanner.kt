package seeyuer.sayin.ui

import com.journeyapps.barcodescanner.ScanOptions

internal fun pairingScanOptions(prompt: String): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt(prompt)
    .setBeepEnabled(false)
    // CaptureActivity's merged fullUser policy handles rotation; never re-lock it here.
    .setOrientationLocked(false)
