package family.seniorlink

import android.app.Application
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import family.seniorlink.core.ConnectionPairing
import family.seniorlink.core.ConnectionInvite
import family.seniorlink.pairing.PairingScannerActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PairingScannerTest {
    @Test fun `scanner stays in app reads only QR and does not save images`() {
        val intent = PairingScannerActivity.options().createScanIntent(RuntimeEnvironment.getApplication())
        assertEquals(PairingScannerActivity::class.java.name, intent.component?.className)
        assertEquals(BarcodeFormat.QR_CODE.name, intent.getStringExtra(Intents.Scan.FORMATS))
        assertFalse(intent.getBooleanExtra(Intents.Scan.BARCODE_IMAGE_ENABLED, true))
        assertFalse(intent.getBooleanExtra(Intents.Scan.BEEP_ENABLED, true))
        assertFalse(intent.getBooleanExtra(Intents.Scan.ORIENTATION_LOCKED, true))
        assertFalse(intent.getBooleanExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, true))
    }

    @Test fun `bundled camera decoder reads the temporary connection invitation offline`() {
        val own = "a".repeat(64)
        val other = "b".repeat(64)
        val invite = ConnectionInvite(publicId = other, name = "Grandad", ticket = "endpoint" + "a".repeat(240),
            endpointId = "c".repeat(64), token = "d".repeat(32), commitment = "e".repeat(64))
        val text = ConnectionPairing.code(invite)
        val size = 480
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val luminance = ByteArray(size * size) { index ->
            if (matrix[index % size, index / size]) 0 else 0xff.toByte()
        }
        val frame = PlanarYUVLuminanceSource(luminance, size, size, 0, 0, size, size, false)
        val decoder = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            .createDecoder(emptyMap<DecodeHintType, Any>())
        val decoded = decoder.decode(frame)
        assertNotNull(decoded)
        assertEquals(text, decoded.text)
        assertEquals(invite, ConnectionPairing.parse(decoded.text, own))
        assertNull(decoder.decode(PlanarYUVLuminanceSource(
            ByteArray(size * size) { 0xff.toByte() }, size, size, 0, 0, size, size, false,
        )))
    }
}
