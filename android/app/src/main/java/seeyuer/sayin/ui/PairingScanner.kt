package seeyuer.sayin.ui

import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.camera.CenterCropStrategy
import seeyuer.sayin.data.PairingInvitation

internal fun pairingScanOptions(prompt: String): ScanOptions = ScanOptions()
    .setCaptureActivity(PairingCaptureActivity::class.java)
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt(prompt)
    .setBeepEnabled(false)
    .setOrientationLocked(false)

class PairingCaptureActivity : CaptureActivity() {
    private var resumed = false

    override fun initializeContent(): DecoratedBarcodeView {
        val scanner = object : DecoratedBarcodeView(this) {
            override fun decodeSingle(callback: BarcodeCallback) {
                var delivered = false
                decodeContinuous { result ->
                    if (delivered || !resumed || isFinishing || isDestroyed) return@decodeContinuous
                    if (runCatching { PairingInvitation.fromQr(result.text) }.isFailure) return@decodeContinuous
                    delivered = true
                    barcodeView.stopDecoding()
                    callback.barcodeResult(result)
                }
            }
        }
        scanner.barcodeView.apply {
            isUseTextureView = false
            previewScalingStrategy = CenterCropStrategy()
        }
        setContentView(scanner)
        return scanner
    }

    override fun onResume() {
        resumed = true
        super.onResume()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }
}
