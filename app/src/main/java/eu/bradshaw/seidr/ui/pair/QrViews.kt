package eu.bradshaw.seidr.ui.pair

import android.util.Size as PixelSize
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import kotlin.math.floor

/** Black on white with a four-module quiet zone, whatever the theme: scanners want contrast. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(
            EncodeHintType.MARGIN to 0,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        ))
    }
    Canvas(modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.White)) {
        val modules = matrix.width + QUIET * 2
        // Whole-pixel modules, so neighbouring squares don't leave hairline seams.
        val cell = floor(size.minDimension / modules)
        val origin = (size.minDimension - cell * matrix.width) / 2
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell, cell))
        }
    }
}

private const val QUIET = 4

/**
 * Camera viewfinder that reports the first QR it reads. Prefers a back camera,
 * then whatever exists (a Quest's passthrough cameras report as external).
 */
@Composable
fun QrScanner(onText: (String) -> Unit, prompt: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onText)
    var noCamera by remember { mutableStateOf(false) }
    val controller = remember { LifecycleCameraController(context) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        controller.cameraSelector = anyCamera
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        // Above the 640x480 default so a code across the room still resolves.
        controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(PixelSize(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        controller.setImageAnalysisAnalyzer(executor, QrAnalyzer { text -> main.execute { callback(text) } })
        controller.initializationFuture.addListener({
            noCamera = runCatching { !controller.hasCamera(anyCamera) }.getOrDefault(true)
        }, main)
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
            controller.clearImageAnalysisAnalyzer()
            executor.shutdown()
        }
    }

    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        if (noCamera) {
            Text("No camera found on this device.", color = Color.White, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp))
        } else {
            AndroidView(
                factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; this.controller = controller } },
                modifier = Modifier.aspectRatio(1f),
            )
            // Over the picture, since on a Quest the view is the passthrough
            // itself and there's nothing else to say what it's for.
            Text(
                prompt, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

private val anyCamera: CameraSelector = CameraSelector.Builder()
    .addCameraFilter { cameras ->
        cameras.sortedBy {
            when (it.lensFacing) {
                CameraSelector.LENS_FACING_BACK -> 0
                CameraSelector.LENS_FACING_EXTERNAL, CameraSelector.LENS_FACING_UNKNOWN -> 1
                else -> 2
            }
        }.take(1)
    }
    .build()

/** Reads the luminance plane with ZXing. */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private var last: String? = null

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val stride = plane.rowStride
            val buffer = plane.buffer.apply { rewind() }
            // The last row can be shorter than the stride; pad so ZXing's bounds hold.
            val data = ByteArray(stride * it.height)
            buffer.get(data, 0, minOf(buffer.remaining(), data.size))
            val source = PlanarYUVLuminanceSource(data, stride, it.height, 0, 0, it.width, it.height, false)
            val text = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            // Report each distinct code once, so a rejected code doesn't re-fire every frame.
            if (text != null && text != last) {
                last = text
                onText(text)
            }
        }
    }
}
