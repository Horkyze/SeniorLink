package family.seniorlink.pairing

import android.view.WindowManager
import android.widget.Button
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.ScanOptions
import family.seniorlink.R

/** Bundled, offline scanner. CaptureActivity owns permission and camera lifecycle. */
class PairingScannerActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.pairing_scanner)
        findViewById<Button>(R.id.cancel_scan).setOnClickListener { finish() }
        return findViewById(R.id.pairing_barcode_scanner)
    }

    companion object {
        fun options() = ScanOptions()
            .setCaptureActivity(PairingScannerActivity::class.java)
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setOrientationLocked(false)
            .setBeepEnabled(false)
            .setBarcodeImageEnabled(false)
            .setPrompt("Point at the other phone's SeniorLink QR code")
            // Return to the pairing form, which explains the manual fallback and settings.
            .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, false)
    }
}
