package com.example.plain.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * カメラの映像からQRコードを探す。
 * 読み取りは端末の中だけで行い（ZXingという小さなオープンソースの部品を使用）、どこにも送信しません。
 */
class QrAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {

    /** 見つけた結果を表示している間は止めておく */
    @Volatile
    var paused = false

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (paused) return
            // 明るさ（Y）の情報だけで読み取れる
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
            val text = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (e: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            if (!text.isNullOrEmpty() && !paused) {
                paused = true
                onFound(text)
            }
        } catch (e: Exception) {
            // 1コマ読めなくても次のコマで再挑戦する
        } finally {
            image.close()
        }
    }
}

/** 読み取った内容の種類 */
sealed class QrContent {
    data class Link(val url: String) : QrContent()
    data class Wifi(val ssid: String, val password: String, val security: String) : QrContent()
    data class Text(val text: String) : QrContent()

    companion object {
        private val OPENABLE = listOf("http://", "https://", "mailto:", "tel:", "sms:", "smsto:", "geo:", "market://")

        fun parse(raw: String): QrContent {
            val t = raw.trim()
            if (t.startsWith("WIFI:", ignoreCase = true)) return parseWifi(t)
            if (OPENABLE.any { t.startsWith(it, ignoreCase = true) }) return Link(t)
            return Text(raw)
        }

        /** 例: WIFI:T:WPA;S:MyNetwork;P:password;; */
        private fun parseWifi(t: String): QrContent {
            val fields = HashMap<String, String>()
            var i = 5
            val sb = StringBuilder()
            var key = ""
            var readingKey = true
            while (i < t.length) {
                val c = t[i]
                when {
                    c == '\\' && i + 1 < t.length -> { sb.append(t[i + 1]); i++ }
                    readingKey && c == ':' -> { key = sb.toString(); sb.clear(); readingKey = false }
                    c == ';' -> {
                        if (key.isNotEmpty()) fields[key.uppercase()] = sb.toString()
                        sb.clear(); key = ""; readingKey = true
                    }
                    else -> sb.append(c)
                }
                i++
            }
            return Wifi(fields["S"] ?: "", fields["P"] ?: "", fields["T"] ?: "")
        }
    }
}
