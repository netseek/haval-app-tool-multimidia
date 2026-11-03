package br.com.redesurftank.havalshisuku.managers

import android.app.Application
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import br.com.redesurftank.App
import br.com.redesurftank.havalshisuku.BuildConfig
import br.com.redesurftank.havalshisuku.diagnostics.ClusterPersistentEventLogger
import br.com.redesurftank.havalshisuku.experimental.CarPlayBootstrapRoot
import br.com.redesurftank.havalshisuku.experimental.CarPlayBridgeAttemptScope
import br.com.redesurftank.havalshisuku.experimental.CarPlayProjectionVisibilityProbe
import br.com.redesurftank.havalshisuku.experimental.CarPlayResidentProof
import br.com.redesurftank.havalshisuku.experimental.CarPlaySafetyPublicationGate
import br.com.redesurftank.havalshisuku.experimental.CarPlayBridgeBootstrapPolicy as Policy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns local preparation, never the lifetime of published OEM callbacks. */
object CarPlayBridgeBootstrapManager {
    private const val TAG = "CarPlayBridgeBoot"
    private const val ENABLED = "carplayBridgeAutomaticEnabled"
    private const val INPUT = "/data/local/tmp/impulse-carplay-input"
    private const val GATE = "persist.haval.carplay.experimental_cold_hook"
    private const val SERVICE_MD5 = "b61f0e4d63535a2ef447be559211697b"
    private const val ROOT = "/data/local/tmp/impulse-carplay"
    data class Status(val enabled: Boolean, val state: String, val message: String)
    private val mutableStatus = MutableStateFlow(Status(false, "disabled", "Preparo automático desativado"))
    val status: StateFlow<Status> = mutableStatus
    private val started = AtomicBoolean()
    private val inFlight = AtomicBoolean()
    private val safetyWriteLock = Any()
    private val publicationGate = CarPlaySafetyPublicationGate()
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "carplay-local-bootstrap") }
    private val publisher = Executors.newSingleThreadScheduledExecutor { Thread(it, "carplay-bootstrap-safety") }
    private val root = CarPlayBootstrapRoot()
    @Volatile private var context: Context? = null
    private data class ActiveAttempt(val identity: Identity, val ticket: String,
        val action: Policy.Action, val coldClearObservedAtMs: Long = 0) {
        fun publicationKey() = CarPlaySafetyPublicationGate.Key(identity.boot, identity.pid, identity.start, ticket)
        fun hasFreshColdClearProof(nowMs: Long) = action == Policy.Action.COLD_ATTACH &&
            coldClearObservedAtMs > 0 && nowMs - coldClearObservedAtMs in 0..2_500
    }
    @Volatile private var activeAttempt: ActiveAttempt? = null
    @Volatile private var safety = Safety()
    private var payload: Payload? = null
    private var installedRevision: String? = null
    private var preflightEnvironment: CarPlayResidentProof.Environment? = null
    private var preflightRevision: String? = null
    private var verifiedServiceBoot: String? = null
    private var nextReadyCheckAt = 0L
    private var lastWaitSignature: String? = null // worker-only; log evidence changes, not every poll
    // Worker-confined. Keep a pending nonce until its lease expires, allowing the peer to finish.
    private val pendingChallenges = mutableMapOf<String, CarPlayResidentProof.Challenge>()
    private val startingBindings = mutableMapOf<String, CarPlayResidentProof.Binding>()

    private data class Identity(val boot: String, val pid: Int, val start: Long) {
        val key get() = "$boot-$pid-$start"
        val directory get() = "$ROOT/runtime/$key"
    }
    private data class Safety(val wall: Long = 0, val elapsed: Long = 0,
        val quiescent: Boolean? = null, val diagnostics: Map<String, Any> = emptyMap())
    private data class Payload(val revision: String, val directory: String,
        val manifest: JSONObject, val source: File) {
        val bridge get() = "$directory/libcarplay_video_bridge.so"
        val injector get() = "$directory/cpbridge-inject"
    }
    private data class Observation(val id: Identity?, val cold: Boolean?, val mappedPath: String?,
        val native: JSONObject, val endpointArmed: Boolean, val nativeOwner: Int, val javaOwner: Int,
        val cpVisual: Boolean, val aaVisual: Boolean,
        val nativeProof: CarPlayResidentProof.Sample? = null,
        val javaProof: CarPlayResidentProof.Sample? = null,
        val visualEvidenceKnown: Boolean = false)

    private fun prefs() = requireNotNull(context).getSharedPreferences("haval_prefs", Context.MODE_PRIVATE)
    @JvmStatic fun isAutomaticEnabled(): Boolean = runCatching {
        (context ?: App.getDeviceProtectedContext()).getSharedPreferences("haval_prefs", Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false)
    }.getOrDefault(false)

    @JvmStatic fun start(base: Context) {
        if (Application.getProcessName() != base.packageName || !BuildConfig.EMBED_FRIDA_TOOLS || BuildConfig.SIMULATOR_MODE) return
        context = base.createDeviceProtectedStorageContext()
        if (!started.compareAndSet(false, true)) return
        publisher.scheduleWithFixedDelay({ runCatching { publishSafety() } }, 0, 500, TimeUnit.MILLISECONDS)
        worker.scheduleWithFixedDelay({
            try { tick() } catch (error: Exception) {
                Log.w(TAG, "Local preparation deferred", error)
                report("waiting", "Aguardando os serviços locais da central")
            }
        }, 0, 3, TimeUnit.SECONDS)
    }

    @JvmStatic fun setAutomaticEnabled(base: Context, enabled: Boolean) {
        context = base.createDeviceProtectedStorageContext()
        prefs().edit().putBoolean(ENABLED, enabled).commit()
        start(base)
        if (enabled && !inFlight.get()) {
            safety = Safety()
            report("waiting", "Aguardando os serviços locais da central")
            worker.execute { nextReadyCheckAt = 0 }
        }
        if (!enabled) {
            // Revoke the file consumed by native code immediately, even if the worker is busy.
            runCatching { writeSafetyPacket(JSONObject().put("eligible", false)) }
            worker.execute {
            runCatching { root.run("setprop $GATE ''") }
            report("disabled", "Preparo automático desativado; a sessão atual é preservada")
            }
        }
    }

    @JvmStatic fun resetFailureCircuit(base: Context) {
        context = base.createDeviceProtectedStorageContext()
        start(base) // published/unknown mappings still cannot be replaced by a reset
        worker.execute {
            try {
            val bundle = payload ?: extractPayload().also { payload = it }
            val state = observe(bundle)
            val identity = requireNotNull(state.id)
            check(state.mappedPath == null && state.nativeOwner == 0 && state.javaOwner == 0)
            root.run(identityGuard(identity) + script("""
                test -r /proc/${identity.pid}/maps || exit 1
                grep -q libcarplay_video_bridge.so /proc/${identity.pid}/maps
                test §? = 1 || exit 1
                if [ -d ${q(attemptMarker(bundle, identity.key))} ]; then rmdir ${q(attemptMarker(bundle, identity.key))} || exit 1; fi
            """))
            check(prefs().edit().remove("cpBridgeCircuitRevision").remove("cpBridgeCircuitIdentity")
                .remove("cpBridgeAttemptBoot")
                .remove("cpBridgeAttemptRevision").remove("cpBridgeAttemptPhase")
                .remove("cpBridgeAttemptTicket").remove("cpBridgeAttemptIdentity")
                .remove("cpBridgeAttemptWarmRestart").remove("cpBridgeAttemptDeadlineElapsed").commit())
            nextReadyCheckAt = 0
            safety = Safety()
            report("waiting", "Aguardando confirmação dos serviços de projeção")
            } catch (error: Exception) {
                Log.w(TAG, "Failure reset deferred; current session is preserved", error)
                report("blocked", "A sessão atual deve terminar antes de repetir o preparo")
            }
        }
    }

    private fun report(state: String, message: String) {
        mutableStatus.value = Status(isAutomaticEnabled(), state, message)
    }

    private fun publishSafety() {
        if (!isAutomaticEnabled() && !inFlight.get()) return
        if (!inFlight.get() && mutableStatus.value.state in setOf("ready", "blocked", "failed")) return
        val beginWall = System.currentTimeMillis()
        val beginElapsed = SystemClock.elapsedRealtime()
        val reading = CarPlayBridgeQuiescence.readDetailed(requireNotNull(context))
        val quiet = reading.quiet
        safety = Safety(beginWall, beginElapsed, quiet, reading.diagnostics)
        val attempt = activeAttempt
        val identity = attempt?.identity
        val coldAttach = attempt?.hasFreshColdClearProof(SystemClock.elapsedRealtime()) == true
        val eligible = isAutomaticEnabled() && inFlight.get() && identity != null && (quiet == true || coldAttach) &&
            System.currentTimeMillis() - beginWall in 0..2500 &&
            SystemClock.elapsedRealtime() - beginElapsed in 0..2500
        val packet = JSONObject().put("bootId", identity?.boot ?: "")
            .put("wallMs", beginWall).put("elapsedMs", beginElapsed)
            .put("targetPid", identity?.pid ?: 0).put("targetStartTime", identity?.start ?: 0)
            .put("ticket", attempt?.ticket ?: "").put("eligible", eligible)
            .put("quiescent", quiet ?: JSONObject.NULL)
            .put("coldAttach", coldAttach)
            .put("preparationState", mutableStatus.value.state)
        synchronized(safetyWriteLock) {
            val enabled = isAutomaticEnabled()
            val phase = prefs().getString("cpBridgeAttemptPhase", "").orEmpty()
            val decision = publicationGate.decide(attempt?.publicationKey(), activeAttempt?.publicationKey(),
                eligible, enabled, inFlight.get(), phase)
            if (decision == CarPlaySafetyPublicationGate.Decision.DROP) return@synchronized
            packet.put("eligible", decision == CarPlaySafetyPublicationGate.Decision.ALLOW)
                .put("enabled", enabled).put("inFlight", inFlight.get()).put("attemptPhase", phase)
            writeSafetyPacketLocked(packet)
        }
    }

    private fun writeSafetyPacket(packet: JSONObject) = synchronized(safetyWriteLock) {
        publicationGate.cancel(activeAttempt?.ticket ?: prefs().getString("cpBridgeAttemptTicket", ""))
        writeSafetyPacketLocked(packet.put("eligible", false))
    }

    private fun writeSafetyPacketLocked(packet: JSONObject) {
        val temporary = File(INPUT, "safety.next")
        temporary.writeText(packet.toString())
        check(temporary.setReadable(true, false))
        check(temporary.renameTo(File(INPUT, "safety.json")))
    }

    private fun tick() {
        if (!isAutomaticEnabled()) {
            report("disabled", "Preparo automático desativado")
            return
        }
        if (SystemClock.elapsedRealtime() < nextReadyCheckAt) return
        val bundle = payload ?: extractPayload().also { payload = it }
        installPayload(bundle)
        if (!ensureInjectorPreflight(bundle)) return
        val observed = try { observe(bundle) } catch (error: Exception) {
            val boot = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()
            if (prefs().getString("cpBridgeAttemptPhase", "") == "preparing" &&
                prefs().getString("cpBridgeAttemptBoot", "") == boot &&
                prefs().getString("cpBridgeAttemptRevision", "") == bundle.revision) {
                failAttempt(bundle, error)
                return
            }
            throw error
        }
        val id = observed.id
        if (id != null && prefs().getString("cpBridgeAttemptBoot", "").let { it != null && it.isNotEmpty() && it != id.boot } &&
            prefs().getString("cpBridgeAttemptRevision", "") == bundle.revision &&
            prefs().getString("cpBridgeAttemptPhase", "") != "ready") {
            prefs().edit().putString("cpBridgeCircuitRevision", bundle.revision)
                .putString("cpBridgeCircuitIdentity", prefs().getString("cpBridgeAttemptIdentity", "")).commit()
        }
        val readyBefore = id != null && prefs().getString("cpBridgeReadyIdentity", "") == id.key &&
            prefs().getString("cpBridgeReadyRevision", "") == bundle.revision
        val phase = when {
            observed.native.optString("phase") == "failed-pinned" -> Policy.Phase.FAILED_PINNED
            observed.native.optString("phase") == "installed-pinned" &&
                (observed.endpointArmed || readyBefore) -> Policy.Phase.READY
            observed.native.optString("phase") == "installed-pinned" -> Policy.Phase.INSTALLED
            observed.native.optString("phase") == "armed" -> Policy.Phase.ARMED
            observed.native.optString("phase") == "failed" -> Policy.Phase.FAILED
            observed.native.optString("phase") in listOf("", "none") -> Policy.Phase.NONE
            else -> Policy.Phase.UNKNOWN
        }
        val current = safety
        val attemptsForProcess = CarPlayBridgeAttemptScope.attemptsForProcess(
            id?.key, prefs().getString("cpBridgeAttemptIdentity", ""))
        val failureCircuitOpen = CarPlayBridgeAttemptScope.failureCircuitOpen(
            id?.key, bundle.revision, prefs().getString("cpBridgeCircuitIdentity", ""),
            prefs().getString("cpBridgeCircuitRevision", ""))
        val deadline = prefs().getLong("cpBridgeAttemptDeadlineElapsed", 0)
        val recovering = id != null && prefs().getString("cpBridgeAttemptPhase", "") == "preparing" &&
            prefs().getString("cpBridgeAttemptIdentity", "") == id.key &&
            prefs().getString("cpBridgeAttemptRevision", "") == bundle.revision &&
            deadline - SystemClock.elapsedRealtime() in 1L..330_000L
        if (recovering && (observed.nativeProof == null || observed.mappedPath == null)) {
            try {
                if (observed.native.optString("phase") in setOf("failed", "failed-pinned")) {
                    error("Native preparation failed: ${observed.native.optString("failure")}")
                }
                CarPlayResidentProof.recovery(observed.native.optString("phase"),
                    observed.mappedPath?.let { it == bundle.bridge }, false)
                recoverAttempt(bundle, observed)
            } catch (error: Exception) { failAttempt(bundle, error) }
            return
        }
        val ownsNative = observed.nativeProof != null
        val ownsJava = observed.javaProof?.armed == true
        val sameImage = observed.mappedPath == bundle.bridge
        if (observed.mappedPath == null && id != null && verifiedServiceBoot != id.boot) {
            if (!root.run("md5sum /vendor/app/TsCarPlayService/TsCarPlayService.apk").trim().startsWith(SERVICE_MD5)) {
                report("waiting", "Aguardando a preparação dos componentes da central")
                return
            }
            verifiedServiceBoot = id.boot
        }
        val observationAgeMs = if (current.elapsed > 0) SystemClock.elapsedRealtime() - current.elapsed else null
        val decision = Policy.evaluate(Policy.Snapshot(
            enabled = true, rootReady = true, assetsVerified = true, bootIdentityKnown = id != null,
            processPresent = id != null, processIdentityMatches = id != null &&
                (observed.mappedPath == null || sameImage), phase = phase,
            ownersAlive = ownsNative && (phase != Policy.Phase.READY || ownsJava),
            hasBridgeMapping = observed.mappedPath != null, coldModulesAbsent = observed.cold,
            coldAttachSafe = observed.cold == true && observed.visualEvidenceKnown &&
                !observed.cpVisual && !observed.aaVisual,
            observationAgeMs = observationAgeMs,
            carPlayActive = if (observed.cpVisual) true else current.quiescent?.not(),
            androidAutoActive = if (observed.aaVisual) true else current.quiescent?.not(),
            attemptsForProcess = attemptsForProcess,
            failureCircuitOpen = failureCircuitOpen,
            revisionMatches = sameImage || observed.mappedPath == null,
            attemptInProgress = recovering
        ))
        when (decision.action) {
            Policy.Action.PRESERVE_READY -> {
                check(id != null)
                prefs().edit().putString("cpBridgeReadyIdentity", id.key)
                    .putString("cpBridgeReadyRevision", bundle.revision)
                    .putString("cpBridgeAttemptPhase", "ready").commit()
                report("ready", "Bridge preparada para esta sessão")
                nextReadyCheckAt = SystemClock.elapsedRealtime() + 30_000
                refreshClientsOnce(bundle, id)
            }
            Policy.Action.PRESERVE_PINNED -> {
                try { recoverAttempt(bundle, observed) }
                catch (error: Exception) { failAttempt(bundle, error) }
            }
            Policy.Action.COLD_ATTACH, Policy.Action.COLD_RESTART -> attempt(bundle, observed, decision.action)
            Policy.Action.BLOCKED -> {
                if (observed.mappedPath == null && decision.reason == Policy.Reason.ATTEMPT_BUDGET_EXHAUSTED) {
                    runCatching { root.run("setprop $GATE ''") }
                }
                if (sameImage && decision.reason in listOf(Policy.Reason.PINNED_FAILURE, Policy.Reason.PRIOR_FAILURE,
                        Policy.Reason.OWNERS_NOT_ALIVE, Policy.Reason.ATTEMPT_NOT_IN_PROGRESS)) {
                    prefs().edit().putString("cpBridgeCircuitRevision", bundle.revision)
                        .putString("cpBridgeCircuitIdentity", id?.key.orEmpty()).commit()
                    runCatching { root.run("setprop $GATE ''") }
                }
                report("blocked", when {
                    observed.mappedPath != null && !sameImage -> "Sessão anterior preservada; aguardando próximo início"
                    else -> "Preparo pausado após uma falha; sessão atual preservada"
                })
                nextReadyCheckAt = SystemClock.elapsedRealtime() + 30_000
            }
            Policy.Action.WAIT_SAFE -> reportPreparationWait(decision.reason, observed, current, observationAgeMs)
            Policy.Action.WAIT_PROCESS -> report("waiting", "Aguardando os serviços do CarPlay")
            else -> report("waiting", "Aguardando os serviços locais da central")
        }
    }

    private fun reportPreparationWait(reason: Policy.Reason, observed: Observation, current: Safety, ageMs: Long?) {
        val ageBucket = when {
            ageMs == null -> "missing"
            ageMs < 0 -> "future"
            ageMs <= Policy.MAX_OBSERVATION_AGE_MS -> "fresh"
            else -> "stale"
        }
        val cpVisual = if (observed.visualEvidenceKnown) observed.cpVisual.toString() else "unknown"
        val aaVisual = if (observed.visualEvidenceKnown) observed.aaVisual.toString() else "unknown"
        val diagnosticSignature = current.diagnostics.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value}" }
        val signature = listOf(reason.name, current.quiescent, cpVisual, aaVisual, ageBucket,
            diagnosticSignature).joinToString("|")
        if (signature != lastWaitSignature) {
            lastWaitSignature = signature
            ClusterPersistentEventLogger.log("carplay_preparation_wait", mapOf(
                "reason" to reason.name, "quiescent" to current.quiescent,
                "cp_visual" to cpVisual, "aa_visual" to aaVisual, "observation_age" to ageBucket
            ) + current.diagnostics.mapKeys { "q_${it.key}" })
        }
        report("waiting", when {
            reason == Policy.Reason.OBSERVATION_NOT_FRESH -> "Aguardando uma leitura recente dos serviços de projeção"
            !observed.visualEvidenceKnown -> "Aguardando confirmação das telas de projeção"
            observed.cpVisual && observed.aaVisual -> "Aguardando liberação das telas do CarPlay e Android Auto"
            observed.cpVisual -> "Aguardando liberação da tela do CarPlay"
            observed.aaVisual -> "Aguardando liberação da tela do Android Auto"
            current.quiescent == null -> "Aguardando confirmação dos serviços de projeção"
            current.quiescent == false -> when (current.diagnostics["reason"]) {
                "DCM_ACTIVE_PROJECTION" -> "Aguardando liberação da projeção na central"
                "BT_AGGREGATE_CONNECTED", "BT_PROFILE_NOT_DISCONNECTED" -> "Aguardando desconexão dos perfis Bluetooth"
                else -> "Aguardando confirmação de desconexão pela central"
            }
            else -> "Aguardando confirmação dos serviços de projeção"
        })
    }

    private fun extractPayload(): Payload {
        val app = requireNotNull(context)
        val metadata = JSONObject(app.assets.open("carplay_bridge/manifest.json").bufferedReader().use { it.readText() })
        check(metadata.getInt("schema") == 1 && metadata.getInt("versionCode") == BuildConfig.VERSION_CODE)
        val revision = metadata.getString("revision")
        check(revision.matches(Regex("[0-9]+-[a-f0-9]{12}-[a-f0-9]{12}")))
        val directory = "$ROOT/versions/$revision"
        check(metadata.getString("remoteDirectory") == directory)
        val source = File(app.filesDir, "carplay-bridge/$revision").apply { mkdirs() }
        val files = metadata.getJSONArray("files")
        check(metadata.getString("policyMode") == CarPlayResidentProof.POLICY_MODE &&
            metadata.getString("patchProfile") == CarPlayResidentProof.PATCH_PROFILE)
        check(metadata.getString("injectorOriginalSha256") == CarPlayResidentProof.ORIGINAL_INJECTOR_SHA &&
            metadata.getString("injectorSha256") == CarPlayResidentProof.PATCHED_INJECTOR_SHA &&
            metadata.getLong("injectorSize") == CarPlayResidentProof.INJECTOR_SIZE)
        val expectedFiles = setOf("libcarplay_video_bridge.so", "cold-launcher-template.js", "endpoint.js",
            "endpoint.js.manifest.json", "cpbridge-inject")
        check(files.length() == expectedFiles.size && (0 until files.length())
            .map { files.getJSONObject(it).getString("name") }.toSet() == expectedFiles)
        for (index in 0 until files.length()) {
            val entry = files.getJSONObject(index)
            val name = entry.getString("name")
            check(name.matches(Regex("[a-zA-Z0-9_.-]+")))
            if (name == "cpbridge-inject") check(entry.getString("sha256") == metadata.getString("injectorSha256") &&
                entry.getLong("size") == metadata.getLong("injectorSize"))
            copyVerified(File(source, name), entry.getString("sha256"), entry.getLong("size")) {
                app.assets.open("carplay_bridge/$name")
            }
        }
        return Payload(revision, directory, metadata, source)
    }

    private fun parseEnvironment(values: Map<String, String>): CarPlayResidentProof.Environment =
        CarPlayResidentProof.Environment(requireNotNull(values["env_boot"]),
            requireNotNull(values["env_system_pid"]).toInt(), requireNotNull(values["env_system_start"]).toLong(),
            requireNotNull(values["env_policy_sha"]), requireNotNull(values["env_injector_sha"]),
            requireNotNull(values["env_injector_fingerprint"])).also(CarPlayResidentProof::checkEnvironment)

    /** A root-owned admission marker precedes the one probe. A pending/crashed probe is never rerun. */
    private fun ensureInjectorPreflight(bundle: Payload): Boolean {
        if (preflightEnvironment != null && preflightRevision == bundle.revision) return true
        var stage = "snapshot-before"
        var beforeEvidence: CarPlayResidentProof.Environment? = null
        var afterEvidence: CarPlayResidentProof.Environment? = null
        var versionEvidence = ""
        fun record(error: Exception? = null) = savePreflightDiagnostic(bundle, stage, error,
            beforeEvidence, afterEvidence, versionEvidence)
        return try {
            record()
            val before = parseEnvironment(fields(root.run(CarPlayResidentProof.environmentSnapshot(bundle.injector), 15_000)))
            beforeEvidence = before
            val directory = CarPlayResidentProof.preflightDirectory(before.bootId, bundle.revision)
            val receipt = "$directory/success.json"
            stage = "admission"; record()
            val admission = root.run(script("""
                command -v timeout >/dev/null 2>&1 || exit 127
                test ! -L ${q("$ROOT/preflight")} || exit 1
                mkdir -p ${q("$ROOT/preflight")} || exit 1
                chown 0:0 ${q("$ROOT/preflight")}; chmod 700 ${q("$ROOT/preflight")}
                test ! -L ${q(directory)} || exit 1
                if [ -e ${q(directory)} ]; then
                  test -d ${q(directory)} && test "§(stat -c %u ${q(directory)})" = 0 && test "§(stat -c %a ${q(directory)})" = 700 || exit 1
                  echo existing
                else
                  mkdir ${q(directory)} || exit 1
                  chown 0:0 ${q(directory)} && chmod 700 ${q(directory)} || exit 1
                  echo admitted
                fi
            """)).trim()
            val environment = when (admission) {
                "existing" -> {
                    stage = "adopt-existing"; record()
                    val text = root.run(script("""
                        test -f ${q(receipt)} && test ! -L ${q(receipt)} || exit 1
                        test "§(stat -c %u ${q(receipt)})" = 0 && test "§(stat -c %a ${q(receipt)})" = 400 || exit 1
                        test "§(wc -c < ${q(receipt)})" -le 4096 || exit 1
                        cat ${q(receipt)}
                    """))
                    check(text == CarPlayResidentProof.preflightReceipt(before, bundle.revision)) { "Preflight receipt no longer matches the environment" }
                    root.run(CarPlayResidentProof.environmentGuard(before, bundle.revision, bundle.injector))
                    before
                }
                "admitted" -> {
                    stage = "probe"; record()
                    val start = JSONObject(CarPlayResidentProof.preflightReceipt(before, bundle.revision))
                        .put("state", "probe-started").toString()
                    val version = root.run(CarPlayResidentProof.environmentStateGuard(before, bundle.injector) +
                        script("""
                            test ! -e ${q("$directory/probe-start.json")} || exit 1
                            printf '%s\n' ${q(start)} > ${q("$directory/probe-start.json")} || exit 1
                            chmod 400 ${q("$directory/probe-start.json")} || exit 1
                            ( ulimit -f 16 || exit 125; exec timeout 10 ${q(bundle.injector)} --version < /dev/null ) > ${q("$directory/probe.stdout")} 2> ${q("$directory/probe.stderr")}
                            cpb_probe_rc=§?
                            printf '%s\n' "§cpb_probe_rc" > ${q("$directory/probe.exit")} || exit 1
                            chmod 400 ${q("$directory/probe.stdout")} ${q("$directory/probe.stderr")} ${q("$directory/probe.exit")} || exit 1
                            if [ "§cpb_probe_rc" != 0 ]; then head -c 4096 ${q("$directory/probe.stderr")} >&2; exit 1; fi
                            test "§(wc -c < ${q("$directory/probe.stdout")})" -le 512 || exit 1
                            cat ${q("$directory/probe.stdout")}
                        """), 15_000).trim()
                    versionEvidence = version
                    stage = "snapshot-after"; record()
                    val after = parseEnvironment(fields(root.run(CarPlayResidentProof.environmentSnapshot(bundle.injector), 15_000)))
                    afterEvidence = after
                    stage = "compare"; record()
                    CarPlayResidentProof.checkPreflight(before, after, version)
                    stage = "receipt"; record()
                    root.run(CarPlayResidentProof.environmentStateGuard(after, bundle.injector) + script("""
                        test ! -e ${q(receipt)} || exit 1
                        printf '%s\n' ${q(CarPlayResidentProof.preflightReceipt(after, bundle.revision))} > ${q("$receipt.next")} || exit 1
                        chown 0:0 ${q("$receipt.next")} && chmod 400 ${q("$receipt.next")} && mv ${q("$receipt.next")} ${q(receipt)} || exit 1
                    """))
                    after
                }
                else -> error("Unknown preflight admission")
            }
            preflightEnvironment = environment
            preflightRevision = bundle.revision
            stage = "success"; record()
            true
        } catch (error: Exception) {
            record(error)
            Log.w(TAG, "Injector preflight unavailable; no cold attempt admitted", error)
            report("blocked", "Verificação do carregador pendente ou interrompida; sessão preservada")
            nextReadyCheckAt = SystemClock.elapsedRealtime() + 30_000
            false
        }
    }

    /** Bounded private evidence; never a readiness receipt or shared safety payload. */
    private fun savePreflightDiagnostic(bundle: Payload, stage: String, error: Exception?,
        before: CarPlayResidentProof.Environment?, after: CarPlayResidentProof.Environment?, version: String) = runCatching {
        fun observed(value: CarPlayResidentProof.Environment?) = value?.let {
            JSONObject(CarPlayResidentProof.preflightReceipt(it, bundle.revision)).put("state", "observed")
        } ?: JSONObject.NULL
        val boot = before?.bootId ?: runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("")
        val packet = JSONObject().put("schema", 1).put("bootId", boot).put("revision", bundle.revision).put("stage", stage)
            .put("elapsedMs", SystemClock.elapsedRealtime()).put("before", observed(before)).put("after", observed(after))
            .put("probeStdout", version.take(512)).put("failed", error != null)
        if (error != null) packet.put("errorType", error.javaClass.simpleName)
            .put("errorMessage", error.message.orEmpty().take(2048))
            .put("rootRawTail", CarPlayBootstrapRoot.rawFailure(error)?.takeLast(16_384) ?: JSONObject.NULL)
        File(requireNotNull(context).filesDir, "carplay-preflight-state.json").writeText(packet.toString())
        if (error != null) {
            val first = File(requireNotNull(context).filesDir, "carplay-preflight-first-failure.json")
            val previous = runCatching { JSONObject(first.readText()) }.getOrNull()
            if (previous == null || previous.optString("bootId") != boot || previous.optString("revision") != bundle.revision)
                first.writeText(packet.toString())
        }
    }

    private fun copyVerified(file: File, digest: String, size: Long, input: () -> InputStream) {
        check(digest.matches(Regex("[a-f0-9]{64}")) && size > 0)
        if (file.isFile && file.length() == size && sha(file) == digest) return
        val temporary = File(file.parentFile, file.name + ".next")
        input().use { stream -> temporary.outputStream().use { stream.copyTo(it, 65536) } }
        check(temporary.length() == size && sha(temporary) == digest)
        check(temporary.renameTo(file))
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun sha(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val bytes = ByteArray(65536); while (true) {
            val count = stream.read(bytes); if (count < 0) break; hash.update(bytes, 0, count)
        } }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun installPayload(bundle: Payload) {
        if (installedRevision == bundle.revision) return
        val files = bundle.manifest.getJSONArray("files")
        val entries = (0 until files.length()).map { files.getJSONObject(it) }.toMutableList()
        fun verify(directory: String) = entries.joinToString("; ") {
            script("""test "§(sha256sum ${q(directory + "/" + it.getString("name"))} | cut -d' ' -f1)" = ${q(it.getString("sha256"))} || exit 1""")
        }
        val temporary = bundle.directory + ".install-" + UUID.randomUUID().toString().replace("-", "")
        val copies = entries.joinToString("; ") {
            "cp ${q(File(bundle.source, it.getString("name")).absolutePath)} ${q(temporary + "/" + it.getString("name"))} || exit 1"
        }
        val traversalParents = listOf(ROOT, "$ROOT/versions", "$ROOT/runtime").joinToString(" ") { q(it) }
        root.run("mkdir -p $traversalParents || exit 1; " +
            "chown 0:0 $traversalParents || exit 1; " +
            "chmod 711 $traversalParents || exit 1; " +
            "if [ -d ${q(bundle.directory)} ]; then ${verify(bundle.directory)}; else " +
            "mkdir ${q(temporary)} || exit 1; $copies; ${verify(temporary)}; " +
            "chown -R 1000:1000 ${q(temporary)}; chmod 500 ${q(temporary)}; chmod 400 ${q(temporary)}/*; " +
            "chown 0:0 ${q("$temporary/cpbridge-inject")}; chmod 500 ${q("$temporary/cpbridge-inject")}; mv ${q(temporary)} ${q(bundle.directory)} || exit 1; fi; " +
            "mkdir -p ${q(INPUT)}; chown ${Process.myUid()}:1000 ${q(INPUT)}; chmod 750 ${q(INPUT)}", 15_000)
        installedRevision = bundle.revision
    }

    private fun observe(bundle: Payload, minimal: Boolean = false, includeVisuals: Boolean = !minimal,
        skipResidentRole: String? = null): Observation {
        val output = root.run(script("""
            printf 'boot='; cat /proc/sys/kernel/random/boot_id
            p=§(pidof com.ts.carplay)
            printf 'pid=%s\n' "§p"
            if [ -n "§p" ] && [ "§{p#* }" = "§p" ]; then
              printf 'stat='; cat /proc/§p/stat
              test -r /proc/§p/maps || exit 1
              grep -qi -e libairplay_ts -e libcarplaystack -e libcoreutils_ts /proc/§p/maps
              cpb_maps_rc=§?
              printf 'cold='; case §cpb_maps_rc in 0) echo no;; 1) echo yes;; *) exit 1;; esac
              printf 'bridge='; grep 'libcarplay_video_bridge.so' /proc/§p/maps | head -n 1; echo
            fi
            if [ ${if (includeVisuals) "1" else "0"} = 1 ]; then
              ${CarPlayProjectionVisibilityProbe.capture()}
              printf '%s\n' "§cpb_projection_visuals"
            fi
            true
        """))
        val values = fields(output)
        val boot = values["boot"]?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
        val pid = values["pid"]?.toIntOrNull()
        val start = startTime(values["stat"].orEmpty(), pid ?: -1)
        val id = if (boot != null && pid != null && start != null) Identity(boot, pid, start) else null
        val mapped = values["bridge"]?.trim()?.takeIf(String::isNotEmpty)?.let {
            if (it.contains("(deleted)")) "deleted" else it.split(Regex("\\s+")).getOrNull(5)
        }
        if (id == null) return Observation(null, null, mapped, JSONObject(), false, 0, 0,
            values["cp_visual"] != "0", values["aa_visual"] != "0",
            visualEvidenceKnown = values["cp_visual"] in setOf("0", "1") && values["aa_visual"] in setOf("0", "1"))
        val run = id.directory
        val details = root.run(script("""
            printf 'native='; if [ -f ${q("$run/cold-state.json")} ]; then cat ${q("$run/cold-state.json")}; else echo '{}'; fi
            if [ ${if (minimal) "0" else "1"} = 1 ]; then
            echo 'endpoint_begin'
            tail -n 40 ${q("$run/endpoint-state.log")} 2>/dev/null || true
            echo 'endpoint_end'
            for c in §(pidof cpbridge-inject); do
              a=§(tr '\000' ' ' < /proc/§c/cmdline)
              a=§{a% }
              role=
              case "§a" in
                ${q("${bundle.injector} -p ${id.pid} -s $run/cold-launcher.js -R qjs")}) role=native_owner;;
                ${q("${bundle.injector} -p ${id.pid} -s ${bundle.directory}/endpoint.js -R qjs")}) role=java_owner;;
              esac
              if [ -n "§role" ]; then printf '%s=%s\n' "§role" "§c"; printf '%s_stat=' "§role"; cat /proc/§c/stat; fi
            done
            fi
            printf 'stat_after='; cat /proc/${id.pid}/stat
            printf 'boot_after='; cat /proc/sys/kernel/random/boot_id
        """))
        val data = fields(details)
        check(startTime(data["stat_after"].orEmpty(), id.pid) == id.start) { "OEM identity changed" }
        check(data["boot_after"] == id.boot) { "Kernel boot changed" }
        fun owner(name: String): Int {
            val candidate = data[name]?.toIntOrNull() ?: return 0
            return if ((startTime(data[name + "_stat"].orEmpty(), candidate) ?: 0) >= id.start) candidate else 0
        }
        val recordedNative = runCatching { JSONObject(data["native"].orEmpty()) }
            .getOrDefault(JSONObject().put("phase", "unknown"))
        val nativeTerminal = recordedNative.optString("phase") in setOf("failed", "failed-pinned")
        val recoveringUnmapped = mapped == null && recordedNative.optString("phase") in setOf("armed", "installing", "installed-pinned") &&
            prefs().getString("cpBridgeAttemptIdentity", "") == id.key &&
            prefs().getString("cpBridgeAttemptRevision", "") == bundle.revision &&
            prefs().getString("cpBridgeAttemptPhase", "") == "preparing"
        val nativeCandidate = if (!minimal && (mapped == bundle.bridge || recoveringUnmapped) && !nativeTerminal && skipResidentRole != "native")
            residentSampleOrNull(bundle, id, "native") else null
        val javaCandidate = if (!minimal && mapped == bundle.bridge && !nativeTerminal && skipResidentRole != "java")
            residentSampleOrNull(bundle, id, "java") else null
        if (nativeCandidate != null || javaCandidate != null) root.run(identityGuard(id) + ":")
        val finalElapsed = SystemClock.elapsedRealtime()
        val finalWall = System.currentTimeMillis()
        val nativeProof = nativeCandidate?.takeIf { CarPlayResidentProof.fresh(it, finalElapsed, finalWall) }
        val javaProof = javaCandidate?.takeIf { CarPlayResidentProof.fresh(it, finalElapsed, finalWall) }
        val nativeState = if (nativeProof != null) JSONObject().put("phase", nativeProof.phase)
            .put("replacements", nativeProof.replacements).put("pinned", nativeProof.pinned)
            .put("failure", if (nativeProof.failureAbsent) JSONObject.NULL else "Resident native failure")
            else recordedNative
        return Observation(id, values["cold"]?.let { when (it) { "yes" -> true; "no" -> false; else -> null } },
            mapped, nativeState, javaProof?.armed == true,
            owner("native_owner"), owner("java_owner"), values["cp_visual"] != "0", values["aa_visual"] != "0",
            nativeProof, javaProof,
            values["cp_visual"] in setOf("0", "1") && values["aa_visual"] in setOf("0", "1"))
    }

    /** Fresh proof for the non-destructive path only; warm restart never uses this admission. */
    private fun refreshColdAttachProof(bundle: Payload, identity: Identity,
        action: Policy.Action): Long {
        if (action != Policy.Action.COLD_ATTACH) return 0
        val current = observe(bundle, minimal = true, includeVisuals = true)
        check(current.id == identity && current.cold == true &&
            current.mappedPath.let { it == null || it == bundle.bridge } &&
            current.visualEvidenceKnown && !current.cpVisual && !current.aaVisual) {
            "Cold attach safety changed"
        }
        return SystemClock.elapsedRealtime()
    }

    private fun attempt(bundle: Payload, before: Observation, action: Policy.Action) {
        val original = before.id ?: return
        val attemptTicket = UUID.randomUUID().toString()
        val initialColdProof = if (action == Policy.Action.COLD_ATTACH && before.cold == true &&
            before.visualEvidenceKnown && !before.cpVisual && !before.aaVisual) {
            SystemClock.elapsedRealtime()
        } else 0L
        synchronized(safetyWriteLock) {
            if (!inFlight.compareAndSet(false, true)) return
            activeAttempt = ActiveAttempt(original, attemptTicket, action, initialColdProof)
        }
        try {
            check(isAutomaticEnabled())
            check(prefs().edit().putString("cpBridgeAttemptBoot", original.boot)
                .putString("cpBridgeAttemptRevision", bundle.revision).putString("cpBridgeAttemptPhase", "preparing")
                .putString("cpBridgeAttemptTicket", attemptTicket)
                .putString("cpBridgeAttemptIdentity", original.key)
                .putBoolean("cpBridgeAttemptWarmRestart", action == Policy.Action.COLD_RESTART)
                .putLong("cpBridgeAttemptDeadlineElapsed", SystemClock.elapsedRealtime() + 330_000).commit())
            report("preparing", "Preparando a conexão com o cluster")
            // Mount-only work belongs to the existing patch manager. Never start a
            // cold attempt against a different/unmounted service APK.
            check(root.run("md5sum /vendor/app/TsCarPlayService/TsCarPlayService.apk").trim().startsWith(SERVICE_MD5))
            // A root-owned atomic marker also survives app death and transport timeouts.
            root.run("mkdir -p ${q("$ROOT/attempts")}; chmod 700 ${q("$ROOT/attempts")}; " +
                "mkdir ${q(attemptMarker(bundle, original.key))} || exit 1; " +
                "chmod 700 ${q(attemptMarker(bundle, original.key))}")
            val existing = root.run("getprop $GATE").trim()
            val nonce = existing.takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: UUID.randomUUID().toString().replace("-", "")
            root.run("setprop $GATE $nonce")
            repeat(8) { if (File(INPUT, "safety.json").isFile) return@repeat; Thread.sleep(100) }
            if (action == Policy.Action.COLD_RESTART) {
                requireFreshSafety(original)
                val checked = observe(bundle)
                check(checked.id == original && checked.mappedPath == null && !checked.cpVisual && !checked.aaVisual)
                root.run(script("""
                    test "§(cat /proc/sys/kernel/random/boot_id)" = ${q(original.boot)} || exit 1
                    test "§(pidof com.ts.carplay)" = ${original.pid} || exit 1
                    ${CarPlayProjectionVisibilityProbe.requireClear()}
                    test -r /proc/${original.pid}/maps || exit 1
                    grep -q libcarplay_video_bridge.so /proc/${original.pid}/maps
                    test §? = 1 || exit 1
                    cpb_stat=§(cat /proc/${original.pid}/stat) || exit 1
                    cpb_tail=§{cpb_stat##*) }; set -- §cpb_tail; shift 19
                    test "§1" = ${original.start} || exit 1
                """) + "\n" + CarPlayResidentProof.environmentGuard(requireNotNull(preflightEnvironment),
                    bundle.revision, bundle.injector) + identityGuard(original) + CarPlaySafetyPublicationGate.warmKillCheck(
                    CarPlaySafetyPublicationGate.Key(original.boot, original.pid, original.start, attemptTicket),
                    "$INPUT/safety.json") + "kill -15 ${original.pid}\n")
            }
            val waitUntil = SystemClock.elapsedRealtime() + 5_000
            var current = before
            while (SystemClock.elapsedRealtime() < waitUntil) {
                current = observe(bundle, minimal = true)
                if (current.id != null && (action == Policy.Action.COLD_ATTACH || current.id != original)) break
                Thread.sleep(80)
            }
            val identity = requireNotNull(current.id)
            check(current.cold == true && (action == Policy.Action.COLD_ATTACH || identity != original))
            val coldProof = refreshColdAttachProof(bundle, identity, action)
            synchronized(safetyWriteLock) {
                activeAttempt = ActiveAttempt(identity, attemptTicket, action, coldProof)
            }
            check(prefs().edit().putString("cpBridgeAttemptIdentity", identity.key).commit())
            if (identity != original) {
                root.run("test ! -e ${q(attemptMarker(bundle, identity.key))} || exit 1; " +
                    "mv ${q(attemptMarker(bundle, original.key))} ${q(attemptMarker(bundle, identity.key))} || exit 1")
            }
            val coldBudget = CarPlayResidentProof.coldGateBudget(identity.start,
                Os.sysconf(OsConstants._SC_CLK_TCK), SystemClock.elapsedRealtime(),
                prefs().getLong("cpBridgeAttemptDeadlineElapsed", 0))
            val run = identity.directory
            val template = File(bundle.source, "cold-launcher-template.js").readText()
            val marker = "\"expectedPid\":1234567890"
            check(template.split(marker).size == 2)
            val exact = template.replace(marker, "\"expectedPid\":${identity.pid}")
                .replace("BOOT_PROCESS_PLACEHOLDER", identity.key)
            val launcher = File(requireNotNull(context).filesDir, "carplay-launcher-${identity.key}.js")
            launcher.writeText(exact)
            root.run(script("""
                mkdir -p ${q(run)}; chown 1000:1000 ${q(run)}; chmod 700 ${q(run)}
                test ! -e ${q("$run/cold-launcher.js")} || exit 1
                cp ${q(launcher.absolutePath)} ${q("$run/cold-launcher.js")} || exit 1
                chown 1000:1000 ${q("$run/cold-launcher.js")}; chmod 400 ${q("$run/cold-launcher.js")}
                test "§(sha256sum ${q("$run/cold-launcher.js")} | cut -d' ' -f1)" = ${q(sha(launcher))}
            """))
            requireFreshSafety(identity)
            launch(bundle, identity, "$run/cold-launcher.js", "$run/native-injector.log", coldBudget.proofDeadlineMs)
            val proof = awaitResident(bundle, identity, "native", coldBudget.proofDeadlineMs, starting = true)
            check(CarPlayResidentProof.nativeArmed(proof) || CarPlayResidentProof.nativeInstalled(proof)) {
                "Native resident peer not prepared before gate release"
            }
            val releaseColdProof = refreshColdAttachProof(bundle, identity, action)
            if (action == Policy.Action.COLD_ATTACH) synchronized(safetyWriteLock) {
                activeAttempt = ActiveAttempt(identity, attemptTicket, action, releaseColdProof)
            }
            requireFreshSafety(identity)
            check(CarPlayResidentProof.fresh(proof, SystemClock.elapsedRealtime(), System.currentTimeMillis())) {
                "Native resident proof expired before gate release"
            }
            // Only a fresh response produced after the exact CLI exited can release the gate.
            val publicationKey = CarPlaySafetyPublicationGate.Key(identity.boot, identity.pid,
                identity.start, attemptTicket)
            val publicationCheck = if (action == Policy.Action.COLD_ATTACH) {
                CarPlaySafetyPublicationGate.coldAttachCheck(publicationKey, "$INPUT/safety.json")
            } else CarPlaySafetyPublicationGate.warmKillCheck(publicationKey, "$INPUT/safety.json")
            root.run(identityGuard(identity) + publicationCheck + CarPlayResidentProof.deadlineGuard(
                coldBudget.releaseDeadlineMs, proof.firstElapsedMs) + "setprop $GATE " +
                UUID.randomUUID().toString().replace("-", ""),
                CarPlayResidentProof.rootTimeout(coldBudget.releaseDeadlineMs, SystemClock.elapsedRealtime()))
            check(SystemClock.elapsedRealtime() < coldBudget.releaseDeadlineMs) { "Cold gate release completed after its deadline" }
            recoverAttempt(bundle, observe(bundle, minimal = true))
            return
        } catch (error: Exception) {
            failAttempt(bundle, error)
        } finally {
            synchronized(safetyWriteLock) {
                inFlight.set(false)
                activeAttempt = null
            }
        }
    }

    private fun recoverAttempt(bundle: Payload, initial: Observation) {
        val identity = initial.id ?: return
        val adopted = synchronized(safetyWriteLock) { inFlight.compareAndSet(false, true) }
        try {
        val attemptTicket = prefs().getString("cpBridgeAttemptTicket", "").orEmpty()
        check(attemptTicket.matches(Regex("[a-f0-9-]{36}")))
        check(prefs().getString("cpBridgeAttemptIdentity", "") == identity.key)
        val action = if (prefs().getBoolean("cpBridgeAttemptWarmRestart", false)) {
            Policy.Action.COLD_RESTART
        } else Policy.Action.COLD_ATTACH
        synchronized(safetyWriteLock) { activeAttempt = ActiveAttempt(identity, attemptTicket, action) }
        val deadline = prefs().getLong("cpBridgeAttemptDeadlineElapsed", 0)
        check(deadline - SystemClock.elapsedRealtime() in 1L..330_000L)
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = observe(bundle, includeVisuals = false)
            check(SystemClock.elapsedRealtime() < deadline) { "Resident preparation deadline expired" }
            check(state.id == identity) { "OEM identity changed during resident preparation" }
            if (state.native.optString("phase") in setOf("failed", "failed-pinned")) {
                error("Native preparation failed: ${state.native.optString("failure")}")
            }
            val recovery = CarPlayResidentProof.recovery(state.native.optString("phase"),
                state.mappedPath?.let { it == bundle.bridge }, state.nativeProof != null)
            if (recovery == CarPlayResidentProof.Recovery.PENDING) {
                // A typed pending response does not revoke a still-live attempt's safety lease.
                Thread.sleep(100)
                continue
            }
            when (state.native.optString("phase")) {
                "installed-pinned" -> {
                    check(CarPlayResidentProof.nativeInstalled(requireNotNull(state.nativeProof))) { "Native resident installation is incomplete" }
                    if (state.endpointArmed && state.javaProof?.armed == true) {
                        check(prefs().edit().putString("cpBridgeReadyIdentity", identity.key)
                            .putString("cpBridgeReadyRevision", bundle.revision)
                            .putString("cpBridgeAttemptPhase", "ready").commit())
                        report("ready", "Bridge preparada para esta sessão")
                        nextReadyCheckAt = SystemClock.elapsedRealtime() + 30_000
                        refreshClientsOnce(bundle, identity)
                        return
                    }
                    if (state.javaProof == null) {
                        check(isAutomaticEnabled())
                        val newlyIssued = !prefs().getBoolean("cpBridgeJavaIssued_${identity.key}", false)
                        if (newlyIssued) {
                            check(prefs().edit().putBoolean("cpBridgeJavaIssued_${identity.key}", true).commit()) { "Could not persist Java launch admission" }
                            launch(bundle, identity, "${bundle.directory}/endpoint.js", "${identity.directory}/java-injector.log")
                        }
                        awaitResident(bundle, identity, "java", minOf(deadline, SystemClock.elapsedRealtime() + 15_000), starting = newlyIssued)
                    }
                }
            }
            Thread.sleep(1_000)
        }
        error("Local preparation timed out")
        } finally {
            if (adopted) synchronized(safetyWriteLock) {
                inFlight.set(false)
                activeAttempt = null
            }
        }
    }

    private fun failAttempt(bundle: Payload, error: Exception) {
        Log.e(TAG, "Automatic preparation stopped; published owners are preserved", error)
        CarPlayBootstrapRoot.rawFailure(error)?.let { raw -> runCatching {
            // Private, bounded evidence only. Raw console output never enters the
            // shared safety packet, preferences, public events or user-facing text.
            File(requireNotNull(context).filesDir, "carplay-root-last-timeout.txt")
                .writeText(raw.takeLast(16_384))
        } }
        val trace = org.json.JSONArray()
        error.stackTrace.take(12).forEach { trace.put(it.toString()) }
        val failure = JSONObject().put("eligible", false)
            .put("failureType", error.javaClass.simpleName)
            .put("failureMessage", error.message ?: "").put("failureStack", trace)
        synchronized(safetyWriteLock) {
            // Preserve a bounded, parsed packet before cancellation overwrites the evidence.
            val priorSafety = runCatching {
                File(INPUT, "safety.json").inputStream().use { input ->
                    val bytes = ByteArray(4097)
                    var count = 0
                    while (count < bytes.size) {
                        val read = input.read(bytes, count, bytes.size - count)
                        if (read < 0) break
                        count += read
                    }
                    check(count in 1..4096) { "Prior safety packet size invalid" }
                    val parsed = JSONObject(String(bytes, 0, count, Charsets.UTF_8))
                    JSONObject().also { safe ->
                        for (key in listOf("eligible", "enabled", "inFlight", "attemptPhase", "preparationState",
                            "bootId", "wallMs", "elapsedMs", "targetPid", "targetStartTime", "ticket", "quiescent",
                            "coldAttach")) {
                            if (parsed.has(key)) safe.put(key, parsed.get(key))
                        }
                    }
                }
            }.getOrNull()
            failure.put("preFailureSafety", priorSafety ?: JSONObject.NULL)
                .put("failureElapsedMs", SystemClock.elapsedRealtime()).put("failureWallMs", System.currentTimeMillis())
            publicationGate.cancel(activeAttempt?.ticket ?: prefs().getString("cpBridgeAttemptTicket", ""))
            pendingChallenges.clear()
            startingBindings.clear()
            // Keep the failure separate: a late diagnostic sample may replace safety.json.
            prefs().edit().putString("cpBridgeCircuitRevision", bundle.revision)
                .putString("cpBridgeCircuitIdentity", activeAttempt?.identity?.key
                    ?: prefs().getString("cpBridgeAttemptIdentity", "").orEmpty())
                .putString("cpBridgeAttemptPhase", "failed")
                .putString("cpBridgeLastFailure", failure.toString()).commit()
            runCatching { writeSafetyPacketLocked(failure) }
        }
        runCatching { root.run("setprop $GATE ''") }
        report("failed", "O preparo foi interrompido; sessão atual preservada")
    }

    private fun refreshClientsOnce(bundle: Payload, identity: Identity) {
        val key = "cpBridgeClientsIssued_${identity.key}"
        if (prefs().getString("cpBridgeAttemptIdentity", "") != identity.key ||
            prefs().getString("cpBridgeAttemptRevision", "") != bundle.revision ||
            !prefs().getBoolean("cpBridgeAttemptWarmRestart", false) || prefs().getBoolean(key, false)) return
        // No retry after an ambiguous transport result or an already connected phone.
        if (!prefs().edit().putBoolean(key, true).commit()) return
        runCatching {
            CarPlayPatchManager.postBootstrapClientRefresh {
                fun safeNow(): Boolean {
                    val sample = safety
                    return isAutomaticEnabled() && sample.quiescent == true && sample.elapsed > 0 &&
                        SystemClock.elapsedRealtime() - sample.elapsed in 0..2500
                }
                if (!safeNow()) false else {
                    val state = observe(bundle)
                    state.id == identity && state.mappedPath == bundle.bridge && state.nativeProof?.let(CarPlayResidentProof::nativeInstalled) == true &&
                        state.javaProof?.armed == true && state.native.optString("phase") == "installed-pinned" &&
                        state.endpointArmed && !state.cpVisual && !state.aaVisual && safeNow()
                }
            }
        }.onFailure { Log.w(TAG, "Native client refresh deferred; bridge owners preserved", it) }
    }

    private fun attemptMarker(bundle: Payload, processIdentity: String) =
        "$ROOT/attempts/${CarPlayBridgeAttemptScope.markerName(processIdentity, bundle.revision)}"

    private fun launch(bundle: Payload, identity: Identity, scriptPath: String, log: String,
        deadline: Long? = null) {
        if (deadline != null) CarPlayResidentProof.rootTimeout(deadline, SystemClock.elapsedRealtime())
        val role = if (scriptPath.endsWith("/cold-launcher.js")) "native" else "java"
        val now = observe(bundle, skipResidentRole = role)
        check(now.id == identity)
        check(isAutomaticEnabled())
        if (scriptPath.endsWith("/cold-launcher.js")) {
            check(now.cold == true && now.mappedPath == null && !now.cpVisual && !now.aaVisual)
            requireFreshSafety(identity)
        }
        val execDeadline = deadline ?: minOf(prefs().getLong("cpBridgeAttemptDeadlineElapsed", 0),
            SystemClock.elapsedRealtime() + 15_000)
        check(execDeadline > SystemClock.elapsedRealtime()) { "Injector launch deadline expired" }
        check(prefs().edit().putLong("cpBridgeExecDeadline_${identity.key}_$role", execDeadline).commit())
        val binding = residentBinding(bundle, identity, role, UUID.randomUUID().toString())
        check(binding.scriptPath == scriptPath && log == "${identity.directory}/$role-injector.log") { "Unexpected resident launch path" }
        val supervisor = File(requireNotNull(context).filesDir, "carplay-$role-supervisor-${identity.key}.sh")
        supervisor.writeText(CarPlayResidentProof.supervisor(binding))
        startingBindings["${identity.key}/$role"] = binding
        val remote = "${identity.directory}/$role-supervisor.sh"
        root.run(identityGuard(identity) + CarPlayResidentProof.environmentGuard(binding.environment,
            bundle.revision, bundle.injector) + script("""
            test -d ${q(attemptMarker(bundle, identity.key))} || exit 1
            test ! -e ${q(remote)} || exit 1
            cp ${q(supervisor.absolutePath)} ${q(remote)} || exit 1
            chown 0:0 ${q(remote)}; chmod 500 ${q(remote)}
            ${deadline?.let { CarPlayResidentProof.deadlineGuard(it) }.orEmpty()}
            nohup setsid sh ${q(remote)} > ${q("${identity.directory}/$role-supervisor.log")} 2>&1 < /dev/null & :
        """), deadline?.let { CarPlayResidentProof.rootTimeout(it, SystemClock.elapsedRealtime()) } ?: 5000L)
    }

    private fun nativeLauncher(bundle: Payload, identity: Identity): String {
        val template = File(bundle.source, "cold-launcher-template.js").readText()
        val marker = "\"expectedPid\":1234567890"
        check(template.split(marker).size == 2) { "Cold launcher PID marker mismatch" }
        return template.replace(marker, "\"expectedPid\":${identity.pid}")
            .replace("BOOT_PROCESS_PLACEHOLDER", identity.key)
    }

    private fun residentBinding(bundle: Payload, identity: Identity, role: String, instance: String): CarPlayResidentProof.Binding {
        check(prefs().getString("cpBridgeAttemptIdentity", "") == identity.key &&
            prefs().getString("cpBridgeAttemptRevision", "") == bundle.revision) { "Resident attempt identity mismatch" }
        val ticket = prefs().getString("cpBridgeAttemptTicket", "").orEmpty()
        val scriptHash = if (role == "native") sha(nativeLauncher(bundle, identity).toByteArray(Charsets.UTF_8))
            else sha(File(bundle.source, "endpoint.js"))
        return CarPlayResidentProof.Binding(role, identity.boot, identity.pid, identity.start,
            bundle.revision, ticket, instance, scriptHash, identity.directory, bundle.directory,
            requireNotNull(preflightEnvironment), prefs().getLong("cpBridgeExecDeadline_${identity.key}_$role", 0))
            .also(CarPlayResidentProof::checkBinding)
    }

    private fun strictString(value: JSONObject, key: String): String =
        (value.opt(key) as? String) ?: error("Resident field $key must be a string")
    private fun strictLong(value: JSONObject, key: String): Long {
        val number = value.opt(key)
        check(number is Int || number is Long) { "Resident field $key must be an integer" }
        return (number as Number).toLong()
    }
    private fun strictPid(value: JSONObject, key: String): Int = strictLong(value, key).let {
        check(it in 1..Int.MAX_VALUE.toLong()) { "Resident field $key is not a PID" }; it.toInt()
    }
    private fun parseBinding(value: JSONObject): CarPlayResidentProof.Binding {
        check(strictLong(value, "schema") == 1L) { "Unknown resident protocol schema" }
        return CarPlayResidentProof.Binding(strictString(value, "role"), strictString(value, "bootId"),
            strictPid(value, "targetPid"), strictLong(value, "targetStartTime"), strictString(value, "revision"),
            strictString(value, "ticket"), strictString(value, "instance"), strictString(value, "scriptSha256"),
            strictString(value, "runtimeDirectory"), strictString(value, "payloadDirectory"),
            requireNotNull(preflightEnvironment), prefs().getLong("cpBridgeExecDeadline_${strictString(value, "bootId")}-${strictPid(value, "targetPid")}-${strictLong(value, "targetStartTime")}_${strictString(value, "role")}", 0))
            .also(CarPlayResidentProof::checkBinding)
    }
    private fun parseReceipt(value: JSONObject, exited: Boolean) = CarPlayResidentProof.Receipt(
        parseBinding(value), strictPid(value, "childPid"), strictLong(value, "childStartTime"),
        strictString(value, "commandSha256"),
        if (exited) strictLong(value, "exitCode").also { check(it in 0..255) }.toInt() else null,
        if (exited) strictLong(value, "exitElapsedMs") else null,
        strictString(value, "commandEvidence"), strictString(value, "supervisorSha256"))

    private fun parseResidentResponse(value: JSONObject): CarPlayResidentProof.Response {
        val samples = value.getJSONArray("samples")
        check(samples.length() in 0..2) { "Invalid resident sample count" }
        return CarPlayResidentProof.Response(parseBinding(value), strictString(value, "nonce"),
            value.opt("disposing") as? Boolean, (0 until samples.length()).map { index ->
                val sample = samples.getJSONObject(index)
                val state = sample.getJSONObject("state")
                CarPlayResidentProof.Sample(strictLong(sample, "sequence"), strictLong(sample, "elapsedMs"),
                    strictLong(sample, "wallMs"), state.optString("phase"),
                    if (state.has("replacements")) strictLong(state, "replacements").also { check(it in 0..9) { "Invalid resident replacement count" } }.toInt() else -1,
                    state.opt("pinned") == true, state.has("failure") && state.isNull("failure"),
                    state.opt("armed") as? Boolean)
            })
    }

    private fun residentSampleOrNull(bundle: Payload, identity: Identity, role: String): CarPlayResidentProof.Sample? {
        if (role == "java" && !prefs().getBoolean("cpBridgeJavaIssued_${identity.key}", false)) return null
        return try { residentSample(bundle, identity, role) }
        catch (error: CarPlayResidentProof.Pending) {
            Log.d(TAG, "$role resident proof pending: ${error.message}")
            null
        }
    }

    private fun awaitResident(bundle: Payload, identity: Identity, role: String, deadline: Long,
        starting: Boolean = false): CarPlayResidentProof.Sample = CarPlayResidentProof.await(
        deadline, { SystemClock.elapsedRealtime() }, { Thread.sleep(80) }
    ) { residentSample(bundle, identity, role, starting, deadline) }

    private fun residentSample(bundle: Payload, identity: Identity, role: String,
        starting: Boolean = false, deadline: Long? = null): CarPlayResidentProof.Sample {
        check(role in setOf("native", "java"))
        val launchPath = "${identity.directory}/$role-launch.json"
        val exitPath = "${identity.directory}/$role-exit.json"
        val admission = startingBindings["${identity.key}/$role"].takeIf { starting }
        val admissionHash = admission?.let(CarPlayResidentProof::supervisorSha256).orEmpty()
        val supervisorPath = "${identity.directory}/$role-supervisor.sh"
        val receipts = root.run(identityGuard(identity) + script("""
            cpb_receipts_pending=no
            cpb_startup_attested=no
            if [ ! -f ${q(launchPath)} ] || [ ! -f ${q(exitPath)} ]; then
              test ${if (admission != null) "1" else "0"} = 1 || exit 1
              if [ -e ${q(exitPath)} ] && [ ! -f ${q(launchPath)} ]; then exit 1; fi
              test "§(stat -c %u ${q(supervisorPath)})" = 0 && test "§(stat -c %a ${q(supervisorPath)})" = 500 || exit 1
              test "§(sha256sum ${q(supervisorPath)} | cut -d' ' -f1)" = ${q(admissionHash)} || exit 1
              cpb_startup_attested=yes
            fi
            cpb_launch=
            cpb_exit=
            for cpb_receipt in ${q(launchPath)} ${q(exitPath)}; do
              if [ ! -e "§cpb_receipt" ]; then continue; fi
              test -f "§cpb_receipt" || exit 1
              test "§(stat -c %u "§cpb_receipt")" = 0 || exit 1
              test "§(stat -c %a "§cpb_receipt")" = 400 || exit 1
              test -s "§cpb_receipt" && test "§(wc -c < "§cpb_receipt")" -le 4096 || exit 1
              cpb_receipt_text=§(cat "§cpb_receipt") || exit 1
              test -n "§cpb_receipt_text" || exit 1
              if [ "§cpb_receipt" = ${q(launchPath)} ]; then
                cpb_launch=§cpb_receipt_text
              else
                cpb_exit=§cpb_receipt_text
              fi
            done
            # Classify the checked snapshots, not the existence before supervisor attestation.
            # The child can publish its exit while the checks above are running.
            if [ -z "§cpb_launch" ] || [ -z "§cpb_exit" ]; then
              test "§cpb_startup_attested" = yes || exit 1
              cpb_receipts_pending=yes
            fi
            if [ -n "§cpb_exit" ] && [ -z "§cpb_launch" ]; then exit 1; fi
            printf 'launch=%s' "§cpb_launch"
            printf '\nexit=%s' "§cpb_exit"
            printf '\nreceipts_pending=%s\n' "§cpb_receipts_pending"
        """) + "\n" + identityGuard(identity) + ":",
            deadline?.let { CarPlayResidentProof.rootTimeout(it, SystemClock.elapsedRealtime()) } ?: 5000L)
        val values = fields(receipts)
        if (values["receipts_pending"] == "yes") {
            if (!values["launch"].isNullOrBlank()) {
                val born = parseReceipt(JSONObject(requireNotNull(values["launch"])), false)
                CarPlayResidentProof.checkLaunch(requireNotNull(admission), born)
            }
            throw CarPlayResidentProof.Pending("Admitted CLI has not completed its birth/exit receipts")
        }
        val launch = parseReceipt(JSONObject(requireNotNull(values["launch"])), false)
        val exited = parseReceipt(JSONObject(requireNotNull(values["exit"])), true)
        val binding = residentBinding(bundle, identity, role, launch.binding.instance)
        CarPlayResidentProof.checkExit(binding, launch, exited)
        startingBindings.remove("${identity.key}/$role")
        // This nonce is created only AFTER validating the exact child's successful exit.
        val challengeKey = "${identity.key}/$role/${binding.instance}"
        val now = SystemClock.elapsedRealtime()
        val challenge = CarPlayResidentProof.challenge(binding.instance, pendingChallenges[challengeKey], now) {
            UUID.randomUUID().toString()
        }.also { pendingChallenges[challengeKey] = it }
        val packet = JSONObject().put("schema", 1).put("instance", challenge.instance)
            .put("nonce", challenge.nonce).put("issuedElapsedMs", challenge.issuedElapsedMs).toString()
        val challengePath = "${identity.directory}/$role-residency-challenge.json"
        val responsePath = "${identity.directory}/$role-residency-response.json"
        val responseText = root.run(identityGuard(identity) +
            deadline?.let { CarPlayResidentProof.deadlineGuard(it) }.orEmpty() + script("""
            printf '%s\n' ${q(packet)} > ${q("$challengePath.next")} || exit 1
            chown 1000:1000 ${q("$challengePath.next")} && chmod 400 ${q("$challengePath.next")} || exit 1
            mv -f ${q("$challengePath.next")} ${q(challengePath)} || exit 1
            cpb_round=0
            cpb_response=
            while [ "§cpb_round" -lt 24 ]; do
              cpb_round=§((cpb_round+1))
              cpb_response=
              if [ -e ${q(responsePath)} ]; then
                test -f ${q(responsePath)} && test -s ${q(responsePath)} && test "§(wc -c < ${q(responsePath)})" -le 8192 || exit 1
                cpb_response=§(cat ${q(responsePath)})
                if printf '%s' "§cpb_response" | grep -Fq ${q("\"nonce\":\"${challenge.nonce}\"")}; then break; fi
              fi
              if printf '%s' "§cpb_response" | grep -Eq '"disposing":true|"phase":"failed(-pinned)?"'; then break; fi
              sleep 0.05
            done
            if [ -z "§cpb_response" ]; then cpb_response=CPB_RESIDENCY_PENDING; fi
        """) + "\n" + identityGuard(identity) + "printf '%s\\n' \"\$cpb_response\"\n",
            deadline?.let { CarPlayResidentProof.rootTimeout(it, SystemClock.elapsedRealtime()) } ?: 5000L)
        if (responseText == "CPB_RESIDENCY_PENDING") throw CarPlayResidentProof.Pending("Fresh residency response not yet available")
        val sample = CarPlayResidentProof.checkResponse(binding, launch, exited, challenge,
            parseResidentResponse(JSONObject(responseText)), SystemClock.elapsedRealtime(), System.currentTimeMillis())
        if (role == "native") check(CarPlayResidentProof.nativeArmed(sample) || CarPlayResidentProof.nativeInstalled(sample)) {
            "Native resident phase invalid: ${sample.phase}/${sample.replacements}/${sample.failureAbsent}"
        } else check(sample.armed == true) { "Java resident peer is not armed" }
        pendingChallenges.remove(challengeKey)
        return sample
    }

    private fun identityGuard(identity: Identity) = script("""
        test "§(cat /proc/sys/kernel/random/boot_id)" = ${q(identity.boot)} || exit 1
        test "§(pidof com.ts.carplay)" = ${identity.pid} || exit 1
        cpb_stat=§(cat /proc/${identity.pid}/stat) || exit 1
        cpb_tail=§{cpb_stat##*) }; set -- §cpb_tail
        case "§1" in Z|X|x) exit 1;; esac
        shift 19; test "§1" = ${identity.start} || exit 1
    """) + "\n"

    private fun requireFreshSafety(identity: Identity) {
        val deadline = SystemClock.elapsedRealtime() + 1200
        while (SystemClock.elapsedRealtime() < deadline) {
            val packet = runCatching { JSONObject(File(INPUT, "safety.json").readText()) }.getOrNull()
            val attempt = activeAttempt
            if (isAutomaticEnabled() && inFlight.get() && attempt != null && attempt.identity == identity &&
                packet != null && packet.optBoolean("eligible") && packet.optString("ticket") == attempt.ticket &&
                packet.optString("bootId") == identity.boot && packet.optInt("targetPid") == identity.pid &&
                packet.optLong("targetStartTime") == identity.start &&
                System.currentTimeMillis() - packet.optLong("wallMs") in 0..2500 &&
                SystemClock.elapsedRealtime() - packet.optLong("elapsedMs") in 0..2500) return
            Thread.sleep(50)
        }
        error("Fresh projection quiescence unavailable")
    }

    private fun fields(text: String): Map<String, String> = text.lineSequence().mapNotNull {
        val index = it.indexOf('='); if (index < 1) null else it.substring(0, index) to it.substring(index + 1)
    }.toMap()
    private fun startTime(stat: String, pid: Int): Long? = runCatching {
        if (stat.substringBefore(" (").toInt() != pid) return null
        val tail = stat.substringAfterLast(") ").split(Regex("\\s+"))
        if (tail[0] in listOf("Z", "X", "x")) null else tail[19].toLong().takeIf { it > 0 }
    }.getOrNull()
    private fun q(value: String) = CarPlayBootstrapRoot.quote(value)
    private fun script(value: String) = value.trimIndent().replace('§', '$')
}
