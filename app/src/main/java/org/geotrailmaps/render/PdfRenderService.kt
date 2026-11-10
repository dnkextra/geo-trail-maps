package org.geotrailmaps.render

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.graphics.createBitmap

/**
 * Renders PDFs in an isolated process (see the manifest): it has no permissions and can't
 * read the app's files, only the PDF it's handed. A crash or exploit in the native renderer
 * stays here, away from maps, tracks, location and recording. One job runs at a time, and
 * one that runs too long ends the process, which [PdfPageRenderer] sees as the worker dying.
 */
class PdfRenderService : Service() {
    private val watchdog = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent): IBinder = object : IPdfRenderService.Stub() {
        override fun openFirstPage(pdf: ParcelFileDescriptor): IPdfPage = job {
            try {
                Page(pdf)
            } catch (e: Exception) {
                pdf.close()
                throw e
            }
        }
    }

    private inner class Page(fd: ParcelFileDescriptor) : IPdfPage.Stub() {
        // The renderer owns fd, and closes it.
        private val renderer = PdfRenderer(fd)
        private val page = renderer.openPage(0)
        private var closed = false

        override fun getWidth() = page.width
        override fun getHeight() = page.height

        override fun render(left: Float, top: Float, scale: Float, widthPx: Int, heightPx: Int): Bitmap = job {
            check(!closed) { "Closed" }
            require(widthPx in 1..MAX_SIDE && heightPx in 1..MAX_SIDE && widthPx.toLong() * heightPx <= MAX_PIXELS) {
                "Too large: $widthPx x $heightPx"
            }
            val bitmap = createBitmap(widthPx, heightPx)
            bitmap.eraseColor(Color.WHITE)
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(-left * scale, -top * scale)
            }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            // In shared memory, so the app maps it rather than copying it out.
            bitmap.asShared().also { if (it !== bitmap) bitmap.recycle() }
        }

        override fun close() = job {
            if (closed) return@job
            closed = true
            page.close()
            renderer.close()
        }
    }

    /**
     * Runs [block] alone (PdfRenderer isn't thread safe) and within [JOB_TIME_LIMIT_MS].
     * Its exceptions reach the caller as IllegalStateException: Binder passes on only a few kinds.
     */
    private fun <T> job(block: () -> T): T = synchronized(this) {
        val overdue = Runnable { Process.killProcess(Process.myPid()) }
        watchdog.postDelayed(overdue, JOB_TIME_LIMIT_MS)
        try {
            block()
        } catch (e: Exception) {
            throw IllegalStateException(e.toString())
        } finally {
            watchdog.removeCallbacks(overdue)
        }
    }

    private companion object {
        const val JOB_TIME_LIMIT_MS = 30_000L
        // The largest render the app asks for is the whole-page base image, at most 3000
        // pixels a side and 6 million pixels.
        const val MAX_SIDE = 4096
        const val MAX_PIXELS = 8_000_000L
    }
}
