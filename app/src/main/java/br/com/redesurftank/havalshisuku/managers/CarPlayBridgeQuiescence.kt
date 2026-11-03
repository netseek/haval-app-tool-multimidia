package br.com.redesurftank.havalshisuku.managers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import ts.car.dcm.common.data.ConnDevice

/**
 * Read-only absence evidence for the automatic CarPlay bootstrap, independent of AA recovery.
 *
 * true means sustained fresh empty DCM replies; false means a device/projection was observed;
 * null means unavailable, malformed, changing, or still settling. An offline paired Bluetooth
 * record requires independent physical OEM Bluetooth absence proof; other devices block.
 * This is a settling policy, not a claim that the OEM exposes an initialization-ready flag.
 */
object CarPlayBridgeQuiescence {
    private const val DCM_PACKAGE = "com.ts.car.dcm.dccontroller"
    private const val DCM_ACTION = "com.ts.car.dcm.dccontroller.connectionmanagerservice.CmService"
    private const val DCM_SERVICE_CLASS = "com.ts.car.dcm.dccontroller.connectionmanagerservice.CmService"
    private const val DCM_INTERFACE = "ts.car.dcm.common.aidl.IConnectionManagementService"
    private const val DCM_APK_SIZE = 237_975L
    private const val DCM_APK_SHA256 = "7859c63e089d6f053df7b4121063e30d4e5f7795cf8fca52a73bebe8a44b9b69"
    private const val GET_ONLINE_DEVICES = 14
    private const val GET_ACTIVE_PROJECTIONS = 45
    private const val BIND_RETRY_MS = 5_000L

    private data class BoundService(val binder: IBinder, val epoch: Long, val boundAtMs: Long)
    private data class FirmwareCheck(val epoch: Long, val source: String, val size: Long,
                                     val modified: Long, val approved: Boolean)
    private data class OnlineSnapshot(val absentOrRetained: Boolean, val retainedAddresses: Set<String>)

    private val stateLock = Any()
    private val readLock = Any()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val window = CarPlayBridgeQuiescenceWindow()
    private var connection: ServiceConnection? = null
    private var boundService: BoundService? = null
    private var nextEpoch = 0L
    private var lastBindAttemptMs = -BIND_RETRY_MS
    // Accessed only under readLock. Rechecked on a new binding or installed APK identity.
    private var firmwareCheck: FirmwareCheck? = null

    /** Call only from the background publisher; binding is posted without waiting for it. */
    fun read(context: Context): Boolean? = readDetailed(context).quiet

    internal fun readDetailed(context: Context): CarPlayQuiescenceReading {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            synchronized(stateLock) { window.reset() }
            return CarPlayQuiescenceDiagnostics.reading(null, CarPlayQuiescenceReason.MAIN_THREAD)
        }
        return synchronized(readLock) {
            val diagnostics = linkedMapOf<String, Any>()
            requestBinding(context.applicationContext)
            val service = synchronized(stateLock) { boundService } ?: return@synchronized CarPlayQuiescenceDiagnostics.reading(null, CarPlayQuiescenceReason.DCM_BINDER_UNAVAILABLE)
            if (!verifyFirmware(context, service.epoch)) {
                synchronized(stateLock) { window.reset() }
                return@synchronized CarPlayQuiescenceDiagnostics.reading(null, CarPlayQuiescenceReason.DCM_FIRMWARE_UNVERIFIED)
            }
            val startedAt = SystemClock.elapsedRealtime()
            val online = readOnlineDevicesEmpty(service.binder, diagnostics)
            val activeEmpty = readActiveProjectionsEmpty(service.binder, diagnostics)
            diagnostics["dcm_online_result_known"] = online != null
            diagnostics["dcm_active_result_known"] = activeEmpty != null
            var bluetooth: CarPlayQuiescenceReading? = null
            val radioEmpty = when {
                online == null -> null
                !online.absentOrRetained -> false
                online.retainedAddresses.isEmpty() -> true
                activeEmpty != true -> null
                else -> CarPlayBluetoothQuiescence.readDetailed(context, online.retainedAddresses)
                    .also { bluetooth = it }.quiet
            }
            bluetooth?.diagnostics?.forEach { (key, value) ->
                if (key != "reason") diagnostics[key] = value
            }
            val sourceReason = when {
                online?.absentOrRetained == false -> CarPlayQuiescenceReason.DCM_RECORD_NOT_RETAINED
                activeEmpty == false -> CarPlayQuiescenceReason.DCM_ACTIVE_PROJECTION
                bluetooth != null -> CarPlayQuiescenceReason.valueOf(bluetooth!!.diagnostics.getValue("reason") as String)
                online == null -> CarPlayQuiescenceReason.DCM_ONLINE_REPLY_UNKNOWN
                activeEmpty == null -> CarPlayQuiescenceReason.DCM_ACTIVE_PROJECTION_UNKNOWN
                else -> CarPlayQuiescenceReason.QUIET
            }
            val completedAt = SystemClock.elapsedRealtime()
            val observed = when {
                radioEmpty == false || activeEmpty == false -> false
                radioEmpty == true && activeEmpty == true -> true
                else -> null
            }
            synchronized(stateLock) {
                if (boundService !== service || !service.binder.isBinderAlive) {
                    window.reset()
                    CarPlayQuiescenceDiagnostics.reading(null, CarPlayQuiescenceReason.DCM_BINDER_CHANGED, diagnostics)
                } else {
                    // Long/blocking transactions cannot contribute stale absence evidence.
                    val fresh = completedAt >= startedAt &&
                        completedAt - startedAt <= CarPlayBridgeQuiescenceWindow.MAX_SAMPLE_GAP_MS
                    val quiet = window.observe(service.epoch, service.boundAtMs, completedAt,
                        if (fresh || observed == false) observed else null)
                    diagnostics["dcm_sample_fresh"] = fresh
                    val reason = when {
                        quiet == true -> CarPlayQuiescenceReason.QUIET
                        quiet == false -> sourceReason
                        !fresh -> CarPlayQuiescenceReason.DCM_SAMPLE_STALE
                        observed == true -> CarPlayQuiescenceReason.DCM_SETTLING
                        else -> sourceReason
                    }
                    CarPlayQuiescenceDiagnostics.reading(quiet, reason, diagnostics)
                }
            }
        }
    }

    private fun requestBinding(context: Context) {
        val candidate: ServiceConnection
        synchronized(stateLock) {
            val now = SystemClock.elapsedRealtime()
            if (connection != null || now - lastBindAttemptMs < BIND_RETRY_MS) return
            lastBindAttemptMs = now
            candidate = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    synchronized(stateLock) {
                        if (connection !== this) return
                        window.reset()
                        boundService = binder?.takeIf { it.isBinderAlive && name != null &&
                            name.packageName == DCM_PACKAGE && name.className == DCM_SERVICE_CLASS }?.let {
                            BoundService(it, ++nextEpoch, SystemClock.elapsedRealtime())
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    synchronized(stateLock) {
                        if (connection !== this) return
                        boundService = null
                        window.reset()
                    }
                    // Keep the binding: Android reconnects this ServiceConnection.
                }

                override fun onBindingDied(name: ComponentName?) = releaseBinding(context, this)
                override fun onNullBinding(name: ComponentName?) = releaseBinding(context, this)
            }
            connection = candidate
        }
        if (!mainHandler.post {
                val stillWanted = synchronized(stateLock) { connection === candidate }
                if (stillWanted) {
                    val didBind = try {
                        context.bindService(Intent(DCM_ACTION).setPackage(DCM_PACKAGE), candidate,
                            Context.BIND_AUTO_CREATE)
                    } catch (_: Exception) {
                        false
                    }
                    if (!didBind) releaseBinding(context, candidate)
                }
            }) {
            synchronized(stateLock) {
                if (connection === candidate) {
                    connection = null
                    boundService = null
                    window.reset()
                }
            }
        }
    }

    private fun releaseBinding(context: Context, candidate: ServiceConnection) {
        synchronized(stateLock) {
            if (connection !== candidate) return
            connection = null
            boundService = null
            window.reset()
        }
        runCatching { context.unbindService(candidate) }
    }

    private fun readOnlineDevicesEmpty(binder: IBinder, diagnostics: MutableMap<String, Any>): OnlineSnapshot? =
        transactRead(binder, GET_ONLINE_DEVICES) { reply ->
            // AIDL's out-Bundle presence marker must be present, not a null/default reply.
            if (reply.readInt() != 1) return@transactRead null
            val devices = Bundle.CREATOR.createFromParcel(reply)
            devices.classLoader = ConnDevice::class.java.classLoader
            val keys = devices.keySet()
            // Force complete unparcelling, including every value, before trusting emptiness.
            val entries = keys.map { devices.get(it) }
            if (entries.any { it !is ConnDevice }) return@transactRead null
            diagnostics["dcm_record_count"] = entries.size
            diagnostics["dcm_records_truncated"] = entries.size > CarPlayQuiescenceDiagnostics.MAX_RECORDS
            val addresses = LinkedHashSet<String>()
            var ignoredInfrastructureRecords = 0
            for ((index, entry) in entries.withIndex()) {
                val device = entry as ConnDevice
                val facts = RetainedBluetoothDevicePolicy.Facts(device.deviceType, device.accepted,
                        device.deviceConnectedState, device.connectedTypeState,
                        device.availableCapabilitys, device.activeCapability,
                        device.activeCapabilityState, device.btAddr, device.isInUsbDeviceMode,
                        listOf(device.usbHidNode, device.iapNode, device.usbNcmNode))
                if (index < CarPlayQuiescenceDiagnostics.MAX_RECORDS) {
                    CarPlayQuiescenceDiagnostics.record(facts).forEach { (key, value) ->
                        diagnostics["dcm_record_${index}_$key"] = value
                    }
                }
                val address = RetainedBluetoothDevicePolicy.addressIfCandidate(facts)
                when {
                    address != null -> addresses.add(address)
                    RetainedBluetoothDevicePolicy.isInactiveDcmInfrastructure(facts) ->
                        ignoredInfrastructureRecords++
                    else -> return@transactRead OnlineSnapshot(false, emptySet())
                }
            }
            diagnostics["dcm_inactive_infrastructure_count"] = ignoredInfrastructureRecords
            OnlineSnapshot(true, addresses)
        }

    private fun readActiveProjectionsEmpty(binder: IBinder, diagnostics: MutableMap<String, Any>): Boolean? =
        transactRead(binder, GET_ACTIVE_PROJECTIONS) { reply ->
            // Exact profiled server: null ONLY when mProjectionConnMap is empty.
            // An empty non-null list can contain pending preconnects without an active device.
            val listPosition = reply.dataPosition()
            val count = reply.readInt()
            diagnostics["dcm_active_list_count"] = count
            diagnostics["dcm_active_list_shape"] = when {
                count == -1 -> CarPlayProjectionListShape.NULL
                count == 0 -> CarPlayProjectionListShape.EMPTY
                count > 0 -> CarPlayProjectionListShape.NONEMPTY
                else -> CarPlayProjectionListShape.INVALID
            }.name
            when (count) {
                -1 -> return@transactRead true
                0 -> return@transactRead null
                else -> if (count < 0) return@transactRead null
            }
            reply.setDataPosition(listPosition)
            val devices = reply.createTypedArrayList(ConnDevice.CREATOR)
                ?: return@transactRead null
            if (devices.any { it == null }) return@transactRead null
            if (devices.isNotEmpty()) false else null
        }

    private fun verifyFirmware(context: Context, epoch: Long): Boolean = try {
        val info = context.packageManager.getServiceInfo(ComponentName(DCM_PACKAGE, DCM_SERVICE_CLASS), 0)
        check(info.packageName == DCM_PACKAGE && info.name == DCM_SERVICE_CLASS)
        val apk = File(info.applicationInfo.sourceDir)
        check(apk.isFile && apk.length() == DCM_APK_SIZE)
        val source = apk.canonicalPath
        val size = apk.length()
        val modified = apk.lastModified()
        val cached = firmwareCheck
        if (cached != null && cached.epoch == epoch && cached.source == source &&
            cached.size == size && cached.modified == modified) {
            cached.approved
        } else {
            val digest = MessageDigest.getInstance("SHA-256")
            apk.inputStream().use { stream ->
                val bytes = ByteArray(16_384)
                while (true) {
                    val count = stream.read(bytes)
                    if (count < 0) break
                    digest.update(bytes, 0, count)
                }
            }
            val approved = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == DCM_APK_SHA256 &&
                apk.length() == size && apk.lastModified() == modified
            firmwareCheck = FirmwareCheck(epoch, source, size, modified, approved)
            approved
        }
    } catch (_: Exception) {
        firmwareCheck = null
        false
    }

    private fun <T> transactRead(binder: IBinder, code: Int, decode: (Parcel) -> T?): T? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DCM_INTERFACE)
            if (!binder.transact(code, data, reply, 0)) return null
            reply.readException()
            val result = decode(reply)
            if (reply.dataAvail() == 0) result else null
        } catch (_: Exception) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}

/** Pure monotonic policy; only the reader's stateLock may mutate its instance. */
internal class CarPlayBridgeQuiescenceWindow {
    companion object {
        const val BIND_SETTLE_MS = 5_000L
        const val EMPTY_SPAN_MS = 2_000L
        const val MAX_SAMPLE_GAP_MS = 1_500L
        const val MIN_EMPTY_SAMPLES = 3
    }

    private var epoch: Long? = null
    private var firstEmptyAtMs: Long? = null
    private var previousAtMs: Long? = null
    private var emptySamples = 0

    fun reset() {
        epoch = null
        firstEmptyAtMs = null
        previousAtMs = null
        emptySamples = 0
    }

    fun observe(epoch: Long, boundAtMs: Long, nowMs: Long, empty: Boolean?): Boolean? {
        val previous = previousAtMs
        if (this.epoch != epoch || previous != null &&
            (nowMs <= previous || nowMs - previous > MAX_SAMPLE_GAP_MS)) reset()
        this.epoch = epoch
        previousAtMs = nowMs
        if (empty != true || boundAtMs < 0 || nowMs < boundAtMs ||
            nowMs - boundAtMs < BIND_SETTLE_MS) {
            firstEmptyAtMs = null
            emptySamples = 0
            return if (empty == false) false else null
        }
        if (firstEmptyAtMs == null) firstEmptyAtMs = nowMs
        emptySamples++
        return if (emptySamples >= MIN_EMPTY_SAMPLES &&
            nowMs - firstEmptyAtMs!! >= EMPTY_SPAN_MS) true else null
    }
}
