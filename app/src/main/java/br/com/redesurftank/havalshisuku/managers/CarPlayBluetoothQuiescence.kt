package br.com.redesurftank.havalshisuku.managers

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
import java.util.Locale

/** Physical OEM Bluetooth observations only; never registers SDK callbacks or changes radio state. */
internal object CarPlayBluetoothQuiescence {
    private const val PACKAGE = "com.ts.car.bluetooth.service"
    private const val SERVICE = "com.ts.car.bluetooth.service.BluetoothService"
    private const val DESCRIPTOR = "ts.car.bluetooth.sdk.IBluetoothManager"
    private const val APK_SIZE = 2_871_765L
    private const val APK_SHA256 = "c99e49b47704e610c044ab01aa530ca451784e9e78885b11cad1c1f45518eac0"
    private const val MAX_DEVICES = 16
    private const val SETTLE_MS = 5_000L
    private const val MAX_READ_MS = 1_500L
    private data class Bound(val binder: IBinder, val epoch: Long, val at: Long)
    private data class Firmware(val epoch: Long, val path: String, val size: Long,
                                val modified: Long, val approved: Boolean)
    private val stateLock = Any()
    private val readLock = Any()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var connection: ServiceConnection? = null
    private var bound: Bound? = null
    private var epoch = 0L
    private var lastBindAt = -SETTLE_MS
    private var firmware: Firmware? = null

    fun read(context: Context, retainedAddresses: Set<String>): Boolean? = readDetailed(context, retainedAddresses).quiet

    internal fun readDetailed(context: Context, retainedAddresses: Set<String>): CarPlayQuiescenceReading {
        if (Looper.myLooper() == Looper.getMainLooper())
            return CarPlayQuiescenceDiagnostics.reading(null, CarPlayQuiescenceReason.MAIN_THREAD)
        if (retainedAddresses.isEmpty() || retainedAddresses.size > MAX_DEVICES ||
            retainedAddresses.any { RetainedBluetoothDevicePolicy.normalizeAddress(it) != it })
            return CarPlayQuiescenceDiagnostics.reading(null, CarPlayQuiescenceReason.BT_INPUT_UNKNOWN)
        return synchronized(readLock) {
            val diagnostics = linkedMapOf<String, Any>("bt_retained_count" to retainedAddresses.size)
            requestBinding(context.applicationContext)
            val current = synchronized(stateLock) { bound } ?: return@synchronized CarPlayQuiescenceDiagnostics.reading(null, CarPlayQuiescenceReason.BT_BINDER_UNAVAILABLE, diagnostics)
            val started = SystemClock.elapsedRealtime()
            if (started - current.at < SETTLE_MS || !verifyFirmware(context, current.epoch)) return@synchronized CarPlayQuiescenceDiagnostics.reading(null, CarPlayQuiescenceReason.BT_SETTLING_OR_FIRMWARE_UNVERIFIED, diagnostics)
            val observed = readDisconnected(current.binder, retainedAddresses, diagnostics)
            val ended = SystemClock.elapsedRealtime()
            synchronized(stateLock) {
                when {
                    bound !== current || !current.binder.isBinderAlive ->
                        CarPlayQuiescenceDiagnostics.reading(null, CarPlayQuiescenceReason.BT_BINDER_CHANGED, diagnostics)
                    ended < started || ended - started > MAX_READ_MS ->
                        CarPlayQuiescenceDiagnostics.reading(null, CarPlayQuiescenceReason.BT_SAMPLE_STALE, diagnostics)
                    else -> CarPlayQuiescenceDiagnostics.reading(observed,
                        CarPlayQuiescenceReason.valueOf(diagnostics.getValue("bt_reason") as String), diagnostics)
                }
            }
        }
    }

    private fun readDisconnected(binder: IBinder, retained: Set<String>, diagnostics: MutableMap<String, Any>): Boolean? {
        fun finish(quiet: Boolean?, reason: CarPlayQuiescenceReason): Boolean? {
            diagnostics["bt_reason"] = reason.name
            return quiet
        }
        val state = adapterState(binder) ?: return finish(null, CarPlayQuiescenceReason.BT_ADAPTER_REPLY_UNKNOWN)
        diagnostics["bt_adapter_state"] = state
        if (state == 10) {
            val after = adapterState(binder)
            if (after != null) diagnostics["bt_adapter_after"] = after
            return if (after == 10) finish(true, CarPlayQuiescenceReason.BT_DISCONNECTED)
                else finish(null, CarPlayQuiescenceReason.BT_ADAPTER_CHANGED)
        }
        if (state != 12) return finish(null, CarPlayQuiescenceReason.BT_ADAPTER_NOT_READY)
        val before = devices(binder, 12) ?: return finish(null, CarPlayQuiescenceReason.BT_DEVICE_REPLY_UNKNOWN)
        diagnostics["bt_known_before_count"] = before.size
        val addresses = before + retained
        diagnostics["bt_queried_count"] = addresses.size
        if (addresses.isEmpty() || addresses.size > MAX_DEVICES) return finish(null, CarPlayQuiescenceReason.BT_DEVICE_SET_UNKNOWN)
        // -1 aggregates all connection managers. Empty is supplementary, never readiness proof.
        val connected = devices(binder, 13, -1) ?: return finish(null, CarPlayQuiescenceReason.BT_AGGREGATE_REPLY_UNKNOWN)
        diagnostics["bt_aggregate_before_count"] = connected.size
        if (connected.isNotEmpty()) return finish(false, CarPlayQuiescenceReason.BT_AGGREGATE_CONNECTED)
        for ((index, address) in addresses.withIndex()) {
            // Exact backend has five real proxies; PAN index 5 is unsupported by TX14.
            for (profile in 0..4) {
                val stateAtProfile = call(binder, 14, 1, {
                    it.writeString(address); it.writeInt(profile)
                }) { it.readInt() } ?: return finish(null, CarPlayQuiescenceReason.BT_PROFILE_REPLY_UNKNOWN)
                diagnostics["bt_device_${index}_profile_$profile"] = stateAtProfile
                when (RetainedBluetoothDevicePolicy.profileAbsence(stateAtProfile)) {
                    true -> Unit
                    false -> return finish(false, CarPlayQuiescenceReason.BT_PROFILE_NOT_DISCONNECTED)
                    null -> return finish(null, CarPlayQuiescenceReason.BT_PROFILE_STATE_UNKNOWN)
                }
            }
        }
        val after = devices(binder, 12) ?: return finish(null, CarPlayQuiescenceReason.BT_DEVICE_REPLY_UNKNOWN)
        diagnostics["bt_known_after_count"] = after.size
        if (before != after) return finish(null, CarPlayQuiescenceReason.BT_DEVICE_SET_CHANGED)
        val connectedAfter = devices(binder, 13, -1)
        if (connectedAfter != null) diagnostics["bt_aggregate_after_count"] = connectedAfter.size
        if (connectedAfter?.isEmpty() != true) return finish(null, CarPlayQuiescenceReason.BT_AGGREGATE_CHANGED)
        val adapterAfter = adapterState(binder)
        if (adapterAfter != null) diagnostics["bt_adapter_after"] = adapterAfter
        return if (adapterAfter == 12) finish(true, CarPlayQuiescenceReason.BT_DISCONNECTED)
            else finish(null, CarPlayQuiescenceReason.BT_ADAPTER_CHANGED)
    }

    private fun adapterState(binder: IBinder): Int? =
        // Profiled server deliberately leaves a fresh CallResult at INVALID_CODE=0 here only.
        call(binder, 4, 0, {}) { it.readInt() }

    private fun devices(binder: IBinder, code: Int, profile: Int? = null): Set<String>? =
        call(binder, code, 1, { parcel -> profile?.let { parcel.writeInt(it) } }) { reply ->
            val count = reply.readInt()
            if (count !in 0..MAX_DEVICES) return@call null
            val addresses = LinkedHashSet<String>()
            repeat(count) {
                if (reply.readInt() != 1) return@call null
                val address = RetainedBluetoothDevicePolicy.normalizeAddress(reply.readString()) ?: return@call null
                reply.readString() // Device name discarded; no identifiers are logged or published.
                repeat(3) { reply.readInt() } // deviceClass, features, enabledFeatures
                if (!addresses.add(address)) return@call null
            }
            addresses
        }

    private fun <T> call(binder: IBinder, code: Int, expectedResult: Int,
                         encode: (Parcel) -> Unit, decode: (Parcel) -> T?): T? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            encode(data)
            if (!binder.transact(code, data, reply, 0)) return null
            reply.readException()
            val value = decode(reply) ?: return null
            if (reply.readInt() != 1) return null
            val result = reply.readInt()
            val message = reply.readString() // Never log it; it may contain device details.
            if (expectedResult == 0 && message != null) return null
            if (result == expectedResult && reply.dataAvail() == 0) value else null
        } catch (_: Exception) {
            null
        } finally {
            reply.recycle(); data.recycle()
        }
    }

    private fun requestBinding(context: Context) {
        val candidate: ServiceConnection
        synchronized(stateLock) {
            val now = SystemClock.elapsedRealtime()
            if (connection != null || now - lastBindAt < SETTLE_MS) return
            lastBindAt = now
            candidate = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    synchronized(stateLock) {
                        if (connection !== this) return
                        bound = binder?.takeIf { it.isBinderAlive && name != null &&
                            name.packageName == PACKAGE && name.className == SERVICE }?.let {
                            Bound(it, ++epoch, SystemClock.elapsedRealtime())
                        }
                    }
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    synchronized(stateLock) { if (connection === this) bound = null }
                }
                override fun onBindingDied(name: ComponentName?) = releaseBinding(context, this)
                override fun onNullBinding(name: ComponentName?) = releaseBinding(context, this)
            }
            connection = candidate
        }
        if (!handler.post {
                if (synchronized(stateLock) { connection === candidate }) {
                    val success = runCatching {
                        context.bindService(Intent(DESCRIPTOR).setComponent(ComponentName(PACKAGE, SERVICE)), candidate, 0)
                    }.getOrDefault(false)
                    if (!success) releaseBinding(context, candidate)
                }
            }) {
            synchronized(stateLock) { if (connection === candidate) { connection = null; bound = null } }
        }
    }

    private fun releaseBinding(context: Context, candidate: ServiceConnection) {
        synchronized(stateLock) {
            if (connection !== candidate) return
            connection = null; bound = null
        }
        runCatching { context.unbindService(candidate) }
    }

    private fun verifyFirmware(context: Context, bindingEpoch: Long): Boolean = try {
        val info = context.packageManager.getServiceInfo(ComponentName(PACKAGE, SERVICE), 0)
        check(info.packageName == PACKAGE && info.name == SERVICE)
        val file = File(info.applicationInfo.sourceDir)
        check(file.isFile && file.length() == APK_SIZE)
        val path = file.canonicalPath
        val size = file.length()
        val modified = file.lastModified()
        val previous = firmware
        if (previous != null && previous.epoch == bindingEpoch && previous.path == path &&
            previous.size == size && previous.modified == modified) previous.approved
        else {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val bytes = ByteArray(16_384)
                while (true) { val count = stream.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
            }
            val approved = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == APK_SHA256 &&
                file.length() == size && file.lastModified() == modified
            firmware = Firmware(bindingEpoch, path, size, modified, approved)
            approved
        }
    } catch (_: Exception) {
        firmware = null
        false
    }
}

/** Pure admission signature. A candidate still requires the physical Bluetooth proof above. */
internal object RetainedBluetoothDevicePolicy {
    data class Facts(val type: Int, val accepted: Int, val state: Int,
                     val connections: Map<Int, Int>, val available: Int, val active: Int,
                     val activeStates: Map<Int, Int>, val address: String?, val usbDeviceMode: Boolean,
                     val transportNodes: List<String?>)

    fun normalizeAddress(value: String?): String? = value?.uppercase(Locale.ROOT)?.takeIf {
        it.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) && it != "00:00:00:00:00:00" && it != "FF:FF:FF:FF:FF:FF"
    }

    fun addressIfCandidate(facts: Facts): String? {
        // Profiled DCM promotes paired BT READY to global ONLINE (1) and can
        // retain it after disconnect. Neither 0 nor 1 proves physical absence.
        if (facts.type !in setOf(1, 2) || facts.accepted != 1 || facts.state !in 0..1 ||
            facts.connections != mapOf(2 to 3) || facts.available != 0 || facts.active != 0 ||
            facts.activeStates.isNotEmpty() || facts.usbDeviceMode ||
            facts.transportNodes.size != 3 || facts.transportNodes.any { !it.isNullOrEmpty() }) return null
        return normalizeAddress(facts.address)
    }

    /**
     * The profiled DCM publishes its own settled Wi-Fi infrastructure row in TX14.
     * It is not a phone or projection: type UNKNOWN, WIFI/READY, only capability 4096,
     * and no active capability, transport node or valid Bluetooth identity. TX45 and
     * the independent visual probe still have to prove that projection is absent.
     * Keep this exact so a new OEM shape fails closed instead of becoming readiness.
     */
    fun isInactiveDcmInfrastructure(facts: Facts): Boolean =
        facts.type == 0 && facts.accepted == 1 && facts.state == 1 &&
            facts.connections == mapOf(3 to 3) && facts.available == 4096 &&
            facts.active == 0 && facts.activeStates.isEmpty() &&
            normalizeAddress(facts.address) == null && !facts.usbDeviceMode &&
            facts.transportNodes.size == 3 && facts.transportNodes.all { it.isNullOrEmpty() }

    fun profileAbsence(state: Int): Boolean? = when (state) {
        0 -> true
        1, 2, 3 -> false
        else -> null
    }
}
