package br.com.redesurftank.havalshisuku.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Session-scoped reader of an EXISTING, exact-profile service. Never starts projection. */
internal class CarPlayAndroidAutoObserver {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "CarPlay-AA-observation").apply { isDaemon = true }
    }
    private val lock = Any()
    private var serial = 0L
    @Volatile private var session: Session? = null
    private var future: ScheduledFuture<*>? = null
    private var connection: ServiceConnection? = null // main only
    private var bindingRequested = false
    private var lastBindAtMs = -RETRY_MS
    private var nextEpoch = 0L
    private var bound: Bound? = null // lock
    private val inRead = AtomicLong()
    private val readSerial = AtomicLong()
    private var firmware: Approval? = null // worker only

    private class Session(val context: Context, val serial: Long, val deadlineMs: Long,
        val publish: (CarPlayAndroidAutoObservationPolicy.Observation) -> Unit) {
        val window = CarPlayAndroidAutoObservationPolicy()
    }
    private class Bound(val binder: IBinder, val epoch: Long, val atMs: Long, val death: IBinder.DeathRecipient)
    private data class Approval(val path: String, val size: Long, val modified: Long, val epoch: Long)

    /** Main thread; the callback is delivered on main and cannot outlive this invocation's session. */
    fun start(context: Context, deadlineMs: Long, publish: (CarPlayAndroidAutoObservationPolicy.Observation) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        stop()
        val current = Session(context.applicationContext, ++serial, deadlineMs, publish)
        session = current
        future = worker.schedule({ poll(current) }, 0, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val previous = session
        session = null
        future?.cancel(false)
        future = null
        inRead.set(0)
        release(previous?.context)
    }

    private fun current(s: Session): Boolean = session === s && SystemClock.elapsedRealtime() < s.deadlineMs

    private fun schedule(s: Session) {
        if (current(s)) future = worker.schedule({ poll(s) }, POLL_MS, TimeUnit.MILLISECONDS)
        else if (session === s) main.post { if (session === s) stop() }
    }

    private fun poll(s: Session) {
        if (!current(s)) { if (session === s) main.post { if (session === s) stop() }; return }
        val slot = synchronized(lock) { bound }
        if (slot == null) {
            val approval = verifyFirmware(s.context, -s.serial)
            main.post {
                if (!current(s)) return@post
                s.publish(unknown(if (approval == null) "FIRMWARE_UNKNOWN" else "SERVICE_UNAVAILABLE"))
                if (approval != null) requestBinding(s, approval)
            }
            schedule(s)
            return
        }
        val approval = verifyFirmware(s.context, slot.epoch)
        if (approval == null) {
            main.post { invalidate(s, slot, "FIRMWARE_UNKNOWN") }
            schedule(s)
            return
        }
        if (!current(s) || !synchronized(lock) { bound === slot }) {
            schedule(s)
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        val read = readSerial.incrementAndGet()
        inRead.set(read)
        main.postDelayed({
            if (current(s) && synchronized(lock) { bound === slot } && inRead.compareAndSet(read, 0)) {
                invalidate(s, slot, "READ_TIMEOUT")
            }
        }, CarPlayAndroidAutoObservationPolicy.MAX_READ_MS + 1)
        val status = readStatus(slot.binder) {
            current(s) && synchronized(lock) { bound === slot } && inRead.get() == read
        }
        val completedAt = SystemClock.elapsedRealtime()
        val result = s.window.observe(true, slot.epoch, slot.atMs, startedAt, completedAt, status)
        val ownsRead = inRead.compareAndSet(read, 0)
        main.post {
            if (!ownsRead || !current(s) || !synchronized(lock) { bound === slot }) return@post
            if (status == null || completedAt - startedAt > CarPlayAndroidAutoObservationPolicy.MAX_READ_MS) {
                invalidate(s, slot, result.reason)
            } else s.publish(result)
        }
        schedule(s)
    }

    private fun requestBinding(s: Session, approval: Approval) {
        if (!current(s) || connection != null || SystemClock.elapsedRealtime() - lastBindAtMs < RETRY_MS ||
            !unchanged(approval)) return
        lastBindAtMs = SystemClock.elapsedRealtime()
        val candidate = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (!current(s) || connection !== this) return
                if (name != ComponentName(PACKAGE, SERVICE) || service == null || !service.isBinderAlive) {
                    invalidate(s, null, "BINDER_UNKNOWN")
                    return
                }
                var record: Bound? = null
                val death = IBinder.DeathRecipient { main.post { record?.let { invalidate(s, it, "BINDER_DIED") } } }
                val fresh = Bound(service, ++nextEpoch, SystemClock.elapsedRealtime(), death)
                record = fresh
                try {
                    service.linkToDeath(death, 0)
                    synchronized(lock) { bound = fresh }
                } catch (_: Exception) { invalidate(s, null, "BINDER_DIED") }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (connection === this) invalidate(s, null, "BINDER_DISCONNECTED")
            }
            override fun onBindingDied(name: ComponentName?) {
                if (connection === this) invalidate(s, null, "BINDING_DIED")
            }
            override fun onNullBinding(name: ComponentName?) {
                if (connection === this) invalidate(s, null, "NULL_BINDING")
            }
        }
        connection = candidate
        bindingRequested = try {
            // flags=0 never creates a missing service. The exact APK's onServiceStart is empty.
            s.context.bindService(Intent(ACTION).setComponent(ComponentName(PACKAGE, SERVICE)), candidate, 0)
        } catch (_: Exception) { false }
        if (!bindingRequested) invalidate(s, null, "SERVICE_UNAVAILABLE")
    }

    private fun invalidate(s: Session, expected: Bound?, reason: String) {
        if (!current(s) || expected != null && !synchronized(lock) { bound === expected }) return
        inRead.set(0)
        release(s.context)
        s.publish(unknown(reason))
    }

    private fun release(context: Context?) {
        val previous = synchronized(lock) { bound.also { bound = null } }
        previous?.let { runCatching { it.binder.unlinkToDeath(it.death, 0) } }
        val old = connection
        connection = null
        val requested = bindingRequested
        bindingRequested = false
        if (old != null && requested && context != null) runCatching { context.unbindService(old) }
    }

    private fun readStatus(binder: IBinder, admitted: () -> Boolean): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            if (!binder.isBinderAlive || binder.interfaceDescriptor != DESCRIPTOR) return null
            // Descriptor lookup is a remote call too: a timeout/stop during it revokes the query.
            if (!admitted()) return null
            data.writeInterfaceToken(DESCRIPTOR)
            if (!binder.transact(GET_LINK_STATUS, data, reply, 0) || reply.dataSize() > 64) return null
            reply.readException()
            if (reply.dataAvail() != Int.SIZE_BYTES) return null
            reply.readInt().takeIf { reply.dataAvail() == 0 }
        } catch (_: Exception) { null }
        finally { reply.recycle(); data.recycle() }
    }

    private fun verifyFirmware(context: Context, epoch: Long): Approval? = try {
        val info = context.packageManager.getServiceInfo(ComponentName(PACKAGE, SERVICE), 0)
        check(info.packageName == PACKAGE && info.name == SERVICE)
        val file = File(info.applicationInfo.sourceDir)
        val value = Approval(file.canonicalPath, file.length(), file.lastModified(), epoch)
        check(file.isFile && value.size == CarPlayAndroidAutoObservationPolicy.APK_SIZE)
        if (firmware != value) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(16384)
                while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == CarPlayAndroidAutoObservationPolicy.APK_SHA256)
            check(unchanged(value))
            firmware = value
        }
        value
    } catch (_: Exception) { firmware = null; null }

    private fun unchanged(value: Approval): Boolean = File(value.path).let {
        it.isFile && it.length() == value.size && it.lastModified() == value.modified
    }
    private fun unknown(reason: String) = CarPlayAndroidAutoObservationPolicy.Observation(null, SystemClock.elapsedRealtime(), reason)

    companion object {
        private const val PACKAGE = "com.ts.androidauto.projectionservice"
        private const val SERVICE = "com.ts.androidauto.projectionservice.AndroidAutoService"
        private const val ACTION = "com.ts.androidauto.action.AndroidAutoService"
        private const val DESCRIPTOR = "com.ts.androidauto.sdk.aidl.LinkCommand"
        private const val GET_LINK_STATUS = 0x15
        private const val POLL_MS = 500L
        private const val RETRY_MS = 3_000L
    }
}
