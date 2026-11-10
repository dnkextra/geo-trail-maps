package org.geotrailmaps.render

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The app's one connection to [PdfRenderService]. It's held while the app is on screen
 * ([hold]) and while PDFs are open in it ([acquire]), so maps usually open without waiting
 * for the worker process to start; once nothing holds it for [IDLE_MS], it's let go and the
 * worker stops.
 */
object PdfWorker {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    @SuppressLint("StaticFieldLeak") // holds only the application context
    private var connection: Connection? = null
    private var holders = 0
    private val idle = Runnable {
        synchronized(lock) {
            if (holders == 0) connection?.let(::disconnect)
        }
    }

    /** Keeps the worker running (starting it now if need be) until closed. Doesn't block. */
    fun hold(context: Context): Closeable {
        synchronized(lock) { take(context) }
        var closed = false
        return Closeable {
            if (!closed) release()
            closed = true
        }
    }

    /** The connected worker; blocks until it has started, so not on the main thread. Pair with [release]. */
    fun acquire(context: Context): Connection {
        val c = synchronized(lock) { take(context) }
        try {
            c.service = IPdfRenderService.Stub.asInterface(c.connected.get(START_TIMEOUT_S, TimeUnit.SECONDS))
            return c
        } catch (e: Exception) {
            // It didn't start in time, or at all: the next acquire tries a new one.
            died(c)
            release()
            throw e
        }
    }

    fun release() = synchronized(lock) {
        if (--holders == 0) main.postDelayed(idle, IDLE_MS)
    }

    /** Forgets [c] once its worker has died, so the next [acquire] starts a new one. */
    fun died(c: Connection) = synchronized(lock) {
        if (connection === c) disconnect(c)
    }

    private fun take(context: Context): Connection {
        holders++
        main.removeCallbacks(idle)
        return connection ?: Connection().also { c ->
            connection = c
            val app = context.applicationContext
            c.context = app
            // Callbacks come on a background thread, so they arrive even if the main one is busy.
            if (!app.bindService(Intent(app, PdfRenderService::class.java), Context.BIND_AUTO_CREATE, Dispatchers.IO.asExecutor(), c)) {
                disconnect(c)
                c.connected.completeExceptionally(IOException("Couldn't start the PDF renderer"))
            }
        }
    }

    private fun disconnect(c: Connection) {
        connection = null
        c.context?.unbindService(c)
        c.context = null
    }

    class Connection internal constructor() : ServiceConnection {
        internal val connected = CompletableFuture<IBinder>()
        internal var context: Context? = null
        lateinit var service: IPdfRenderService
            internal set

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            connected.complete(service)
        }

        // The worker died: the next acquire starts a new one rather than wait for this to restart.
        override fun onServiceDisconnected(name: ComponentName) = died(this)

        override fun onBindingDied(name: ComponentName) {
            connected.completeExceptionally(IOException("The PDF renderer died while starting"))
            died(this)
        }

        override fun onNullBinding(name: ComponentName) {
            connected.completeExceptionally(IOException("The PDF renderer didn't start"))
            died(this)
        }
    }

    private const val START_TIMEOUT_S = 10L
    private const val IDLE_MS = 30_000L
}
