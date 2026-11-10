package com.tote.app.pairing

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * QR-code generator for the pairing payload.
 *
 * Uses the ZXing core library (no Android-specific deps, works in JVM tests
 * too). Renders the `tote://pair/<base64>` string as a monochrome QR bitmap
 * at the requested size.
 */
object QrCodeGenerator {

    /**
     * Render [content] as a QR bitmap.
     *
     * @param content The string to encode (e.g., PairingPayload.toQrString())
     * @param size Desired bitmap width/height in pixels (QR will be square)
     * @param margin QR quiet-zone margin in modules (default 2 per spec)
     * @return A monochrome [Bitmap]
     */
    fun generate(content: String, size: Int = 512, margin: Int = 2): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to margin,
            EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
