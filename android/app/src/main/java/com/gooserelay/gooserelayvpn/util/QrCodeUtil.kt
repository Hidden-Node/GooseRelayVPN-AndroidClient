package com.gooserelay.gooserelayvpn.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodeUtil {

    private val DECODE_HINTS: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
        DecodeHintType.TRY_HARDER to true
    )

    fun encodeAsBitmap(
        content: String,
        sizePx: Int = 800,
        margin: Int = 1
    ): Bitmap {
        require(content.isNotBlank()) { "QR content must not be blank" }
        require(sizePx > 0) { "sizePx must be positive" }

        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to margin
        )

        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height) { i ->
            if (matrix.get(i % width, i / width)) 0xff000000.toInt() else 0xffffffff.toInt()
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    fun syncDecodeQRCode(bitmap: Bitmap?): String? {
        if (bitmap == null) return null
        val safeBitmap = if (bitmap.config?.name == "HARDWARE") {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        } else {
            bitmap
        }
        return try {
            val width = safeBitmap.width
            val height = safeBitmap.height
            val pixels = IntArray(width * height)
            safeBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val qrReader = QRCodeReader()

            try {
                qrReader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), DECODE_HINTS).text
            } catch (_: ReaderException) {
                try {
                    qrReader.decode(BinaryBitmap(GlobalHistogramBinarizer(source.invert())), DECODE_HINTS).text
                } catch (_: ReaderException) {
                    try {
                        qrReader.decode(BinaryBitmap(HybridBinarizer(source)), DECODE_HINTS).text
                    } catch (_: ReaderException) {
                        qrReader.decode(BinaryBitmap(HybridBinarizer(source.invert())), DECODE_HINTS).text
                    }
                }
            }?.trim()?.ifBlank { null }
        } catch (_: Exception) {
            null
        } finally {
            if (safeBitmap !== bitmap) {
                safeBitmap.recycle()
            }
        }
    }

    fun decodeFromUri(context: Context, uri: Uri): String? {
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            val maxDimension = maxOf(options.outWidth, options.outHeight)
            val sampleSize = maxOf(1, maxDimension / 600)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
            try {
                syncDecodeQRCode(bitmap)
            } finally {
                bitmap?.recycle()
            }
        }.getOrNull()
    }

    fun decodeFromBitmap(bitmap: Bitmap): Result<String> = runCatching {
        val text = syncDecodeQRCode(bitmap)
        require(!text.isNullOrBlank()) { "Decoded QR code is empty" }
        text
    }
}
