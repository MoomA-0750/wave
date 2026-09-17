package xyz.mooma0750.wave.connect

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** ペアリングの QR を表示する（1モジュール = 1ピクセルの画像を、ぼかさずに拡大する）。 */
@Composable
fun QrImage(
    text: String,
    modifier: Modifier = Modifier,
) {
    val bitmap =
        remember(text) {
            val matrix =
                QRCodeWriter().encode(
                    text,
                    BarcodeFormat.QR_CODE,
                    0,
                    0,
                    mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
                )
            val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    Image(bitmap, contentDescription = "ペアリングの QR", modifier = modifier, filterQuality = FilterQuality.None)
}

/**
 * カメラで QR を読む。最初に読めた1つだけを onResult に渡す（中身の検証は呼び出し側）。
 * 画像は端末の中で解くだけで、保存も送信もしない。
 */
@Composable
fun QrScanner(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnResult by rememberUpdatedState(onResult)
    val done = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    // 自分が束ねた use case だけを外す（止めた executor に画像を渡させない）
    val bound = remember { arrayOfNulls<androidx.camera.core.UseCase>(2) }
    DisposableEffect(Unit) {
        onDispose {
            done.set(true)
            (bound[1] as? ImageAnalysis)?.clearAnalyzer()
            if (providerFuture.isDone) runCatching { providerFuture.get().unbind(*bound.filterNotNull().toTypedArray()) }
            executor.shutdown()
        }
    }
    AndroidView(
        factory = { viewContext ->
            val view = PreviewView(viewContext)
            providerFuture.addListener({
                if (done.get()) return@addListener
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val reader = QRCodeReader()
                analysis.setAnalyzer(executor) { image ->
                    val text = image.use { if (done.get()) null else decode(reader, it) }
                    if (text != null && done.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(viewContext).execute { latestOnResult(text) }
                    }
                }
                bound[0] = preview
                bound[1] = analysis
                runCatching { provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
            }, ContextCompat.getMainExecutor(viewContext))
            view
        },
        modifier = modifier,
    )
}

private fun decode(
    reader: QRCodeReader,
    image: ImageProxy,
): String? {
    // 輝度（Y 面）だけを使う。QR は向きに依らず読める
    val plane = image.planes[0]
    val width = image.width
    val height = image.height
    val buffer = plane.buffer
    val data = ByteArray(width * height)
    for (row in 0 until height) {
        buffer.position(row * plane.rowStride)
        buffer.get(data, row * width, width)
    }
    val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    return try {
        reader.decode(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
