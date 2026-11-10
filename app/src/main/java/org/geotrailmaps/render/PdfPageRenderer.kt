package org.geotrailmaps.render

import android.content.Context
import android.graphics.Bitmap
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders regions of the first page of a PDF, in [PdfRenderService]'s isolated process.
 * Opening may wait for that process to start (see [PdfWorker]), so it mustn't be on the main
 * thread. Renders go to it one at a time, on a single-threaded dispatcher.
 *
 * If the worker dies (the system may stop it, a PDF may crash it, or a render may run too
 * long) it's restarted and the render tried again, but only if this PDF had rendered there
 * since it started: a PDF that a fresh worker dies on gets null renders, as when closed.
 * So do renders the worker refuses or fails, rather than crash the app.
 */
class PdfPageRenderer(context: Context, private val file: File) : Closeable {
    private val context = context.applicationContext
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    /** Null once the worker died and couldn't be restarted. */
    private var worker: Worker? = Worker()
    /** True once this PDF has rendered in [worker]. */
    private var proven = false

    /** Page size in points. */
    val width: Int = worker!!.width
    val height: Int = worker!!.height

    private var closed = false

    /** Renders the page region starting at ([left], [top]) points, at [scale] pixels per point. */
    suspend fun render(left: Float, top: Float, scale: Float, widthPx: Int, heightPx: Int): Bitmap? =
        withContext(dispatcher) { if (closed) null else renderNow(left, top, scale, widthPx, heightPx) }

    fun renderThumbnail(maxSize: Int): Bitmap {
        val scale = maxSize.toFloat() / max(width, height)
        return renderNow(0f, 0f, scale, (width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
            ?: throw IOException("The PDF renderer stopped")
    }

    private fun renderNow(left: Float, top: Float, scale: Float, widthPx: Int, heightPx: Int): Bitmap? {
        val w = worker ?: return null
        return try {
            w.page.render(left, top, scale, widthPx, heightPx).also { proven = true }
        } catch (e: DeadObjectException) {
            w.died()
            w.close()
            worker = null
            if (!proven) return null
            Log.w(TAG, "PDF renderer died; restarting it", e)
            worker = runCatching { Worker() }.getOrNull()
            proven = false
            renderNow(left, top, scale, widthPx, heightPx)
        } catch (e: Exception) {
            Log.w(TAG, "PDF render failed", e)
            null
        }
    }

    override fun close() {
        // Queue behind any render in progress.
        CoroutineScope(dispatcher).launch {
            if (closed) return@launch
            closed = true
            worker?.close()
            worker = null
        }
    }

    /** [file], open in [PdfRenderService]. */
    private inner class Worker {
        private val connection = PdfWorker.acquire(context)
        val page: IPdfPage
        val width: Int
        val height: Int

        init {
            try {
                page = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { connection.service.openFirstPage(it) }
                width = page.width
                height = page.height
            } catch (e: Exception) {
                if (e is DeadObjectException) PdfWorker.died(connection)
                PdfWorker.release()
                throw e
            }
        }

        fun died() = PdfWorker.died(connection)

        fun close() {
            runCatching { page.close() }
            PdfWorker.release()
        }
    }

    private companion object {
        const val TAG = "PdfPageRenderer"
    }
}
