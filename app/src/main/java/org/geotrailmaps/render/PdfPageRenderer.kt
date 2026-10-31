package org.geotrailmaps.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders regions of the first page of a PDF. PdfRenderer is not thread safe, so all
 * work runs on a single-threaded dispatcher.
 */
class PdfPageRenderer(file: File) : Closeable {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val page = renderer.openPage(0)

    /** Page size in points. */
    val width: Int = page.width
    val height: Int = page.height

    private var closed = false

    /** Renders the page region starting at ([left], [top]) points, at [scale] pixels per point. */
    suspend fun render(left: Float, top: Float, scale: Float, widthPx: Int, heightPx: Int): Bitmap? =
        withContext(dispatcher) { if (closed) null else renderNow(left, top, scale, widthPx, heightPx) }

    fun renderThumbnail(maxSize: Int): Bitmap {
        val scale = maxSize.toFloat() / max(width, height)
        return renderNow(0f, 0f, scale, (width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
    }

    private fun renderNow(left: Float, top: Float, scale: Float, widthPx: Int, heightPx: Int): Bitmap {
        val bitmap = createBitmap(widthPx, heightPx)
        bitmap.eraseColor(Color.WHITE)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(-left * scale, -top * scale)
        }
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    override fun close() {
        // Queue behind any render in progress.
        CoroutineScope(dispatcher).launch {
            if (closed) return@launch
            closed = true
            page.close()
            renderer.close()
            fd.close()
        }
    }
}
