package io.github.ccch1mneyyy.dsh_app.runtime

import io.github.ccch1mneyyy.dsh_app.BuildConfig
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings
import io.github.ccch1mneyyy.dsh_app.R
import android.content.Context
import io.github.ccch1mneyyy.dsh_app.performance.PerformancePolicy
import android.content.Intent
import android.net.LinkProperties
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.ccch1mneyyy.dsh_app.phone.PhoneControl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal fun acceptsRunReady(
    sourceIsCurrent: Boolean,
    phase: RuntimeController.Phase,
    runGeneration: Int,
    stopGeneration: Int,
    resetting: Boolean,
): Boolean =
    sourceIsCurrent && phase != RuntimeController.Phase.STOPPING && stopGeneration < runGeneration && !resetting

/**
 * Process-wide owner of the local runtime: install state, the DSH process, logs, and the
 * failure detection / safe mode around it.
 * The Flutter plugin and the foreground service both talk to this singleton.
 */
class RuntimeController private constructor(private val app: Context) : DshProcess.Listener {

    enum class Phase(val wire: String) {
        NOT_INSTALLED("notInstalled"), INSTALLING("installing"), INSTALLED("installed"),
        STARTING("starting"), RUNNING("running"), STOPPING("stopping"), ERROR("error"),
    }

    fun interface EventSink {
        fun emit(event: Map<String, Any?>)
    }

    internal class StopRequest(
        val runGeneration: Int,
        val startTicketOwner: Long?,
        private val onDone: (Throwable?) -> Unit,
    ) {
        private val completed = java.util.concurrent.atomic.AtomicBoolean()
        fun belongsTo(generation: Int): Boolean = runGeneration == generation
        fun belongsToStart(ticket: Long): Boolean = startTicketOwner == ticket
        fun finish(error: Throwable?) {
            if (completed.compareAndSet(false, true)) onDone(error)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-runtime") }
    private val stopRequestIds = AtomicLong()
    private val stopRequests = ConcurrentHashMap<Long, StopRequest>()
    private val sinks = LinkedHashSet<EventSink>()
    private val logDir = File(app.filesDir, "runtime/logs")
    private val runtimeLogs = RuntimeLogs(
        File(logDir, "dsh.log"), SystemClock::elapsedRealtime,
        post = { action -> main.post { action() } },
        deliver = { entries ->
            val event = mapOf("type" to "logs", "entries" to entries.map { it.toMap() })
            val targets = synchronized(sinks) { sinks.toList() }
            targets.forEach { it.emit(event) }
        },
    )
    private val lkgFile = File(app.filesDir, "runtime/lkg/lkg.json")
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val manifest: RuntimeManifest by lazy { RuntimeManifest.load(app) }
    private val installer by lazy { RuntimeInstaller(app, manifest, ::note, rootRuntime = BuildConfig.ROOT_RUNTIME) }
    @Volatile private var process: DshProcess? = null
    @Volatile private var lastLaunch: LaunchDiagnostics? = null
    /** The start in progress is turning PRoot's fake hard links into real files (see [L2sMigration]). */
    @Volatile private var tidyingFiles = false

    @Volatile var phase: Phase = Phase.NOT_INSTALLED
        private set
    @Volatile private var baseUrl: String? = null
    @Volatile private var token: String? = null
    @Volatile private var error: String? = null
    @Volatile private var pendingEnv: Map<String, String> = emptyMap()
    @Volatile private var pendingPhoneStorage = false
    internal val rootStartupGate = RootStartupGate()
    private class PendingInstall(
        val ticket: RootStartupGate.Ticket,
        val dropKept: Boolean,
        val unpack: Boolean,
        private val onDone: (Throwable?) -> Unit,
    ) {
        private val finished = java.util.concurrent.atomic.AtomicBoolean()
        fun finish(error: Throwable?) { if (finished.compareAndSet(false, true)) onDone(error) }
    }
    private var pendingInstall: PendingInstall? = null
    /** Whether the managed AGENTS.md tells the model about ```dsh-ui views (settings, on by default). */
    @Volatile private var pendingVisualReplies = true

    // Safe mode and failure detection. State that must survive the app being killed lives in [prefs].
    @Volatile private var safeState = SafeState.parse(prefs.getString(K_SAFE_STATE, null))
    @Volatile private var safeReason: String? = prefs.getString(K_SAFE_REASON, null)
    @Volatile private var strikes = prefs.getInt(K_STRIKES, 0)
    /** Latest unexpected exit; the failure card shows it until a later run gets going. */
    @Volatile private var failure: RunFailure? = readFailure(K_FAILURE)

    /** Latest failure of the main profile (normal mode): what safe mode is there to fix. */
    @Volatile private var mainFailure: RunFailure? = readFailure(K_MAIN_FAILURE)

    /** Mode of the current (or last) launched process. */
    @Volatile private var runSafe = false
    @Volatile private var runGen = 0
    @Volatile private var activeStartTicket: Long? = null
    @Volatile private var stopGen = -1
    @Volatile private var resetting = false
    @Volatile private var pendingRootCleanup: DshProcess? = null
    // Retire skill writes at the stop request, before the Service receives ACTION_STOP.
    // This gate does not change process lifecycle or A5's ownership/launch decisions.
    private val managedSkillGate = ManagedSkillRuntimeGate()
    private val managedSkills = ManagedSkillQueue(
        execute = { action -> worker.execute { action() } },
        deliver = { action ->
            if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
        },
    )
    private val autoRestarts = ArrayDeque<Long>()
    @Volatile private var readyAt: Long? = null
    @Volatile private var healthyAt: Long? = null

    /** The main profile as it was when the current normal run booted; becomes the LKG when healthy. */
    @Volatile private var bootProfile: ProfileSnapshot? = null
    private val runTail = ArrayDeque<String>()
    private val networkAccess = NetworkAccess()
    private val dnsLog = DnsConfigLog()

    /** Start → spawn → listening, logged in one line when DSH comes up. */
    private val timeline = StartupTimeline.forThisProcess()

    /** The process being started or running is a safe-mode one. */
    val safeRun: Boolean get() = runSafe
    internal val runtimeGeneration: Int get() = runGen

    init {
        phase = try {
            if (installer.isCurrent()) Phase.INSTALLED else Phase.NOT_INSTALLED
        } catch (e: Exception) {
            error = "manifest: ${e.message}"
            Phase.ERROR
        }
    }

    fun addSink(sink: EventSink) = synchronized(sinks) { sinks += sink }
    fun removeSink(sink: EventSink) = synchronized(sinks) { sinks -= sink }

    fun status(): Map<String, Any?> = mapOf(
        "abi" to (runCatching { manifest.abi }.getOrNull() ?: Build.SUPPORTED_ABIS.firstOrNull()),
        "bundledVersion" to runCatching { manifest.runtimeVersion }.getOrNull(),
        "installedVersion" to runCatching { installer.installedVersion() }.getOrNull(),
        "dshVersion" to runCatching { manifest.dshVersion }.getOrNull(),
        "nodeVersion" to runCatching { manifest.nodeVersion }.getOrNull(),
        "phase" to phase.wire,
        "baseUrl" to baseUrl,
        "token" to token,
        "managedSkillGeneration" to managedSkillGate.generationFor(token),
        "error" to error,
        "mode" to if (currentModeSafe()) "safe" else "normal",
        "safeState" to safeState.wire,
        "safeReason" to safeReason,
        "strikes" to strikes,
        "autoSafe" to autoSafe(),
        "failure" to failure?.toMap(),
        "mainFailure" to mainFailure?.toMap(),
        "healthyAt" to healthyAt,
        "networkBlocked" to networkAccess.isBlocked,
        "networkBlockedAt" to networkAccess.blockedAt,
        "directEngineEnabled" to directEngine().enabled,
        "directEngineAvailable" to directEngineAvailable(),
        "directEngineActive" to (process?.executionMode == "direct"),
        "directEngineFallbackReason" to lastLaunch?.fallbackReason,
        "currentExecutionMode" to process?.executionMode,
        "launchSelectedMode" to lastLaunch?.selectedMode,
        "launchSpawned" to (lastLaunch?.spawned == true),
        "launchFailureReason" to lastLaunch?.failureReason,
        "tidyingFiles" to tidyingFiles,
        "rootRuntime" to BuildConfig.ROOT_RUNTIME,
    )

    private fun directEngine() = DirectLaunch.Preference(
        saved = if (prefs.contains(K_DIRECT_ENGINE)) prefs.getBoolean(K_DIRECT_ENGINE, false) else null,
        available = directEngineAvailable(),
        failedRuntime = prefs.getString(K_DIRECT_FAILED, null),
        runtime = runCatching { manifest.runtimeVersion }.getOrNull(),
    )
    // Root builds launch through the root backend only: Direct never applies, and with it the
    // launch-failure fallback back to PRoot is unreachable (SafePolicy.directLaunchFailed).
    private fun directEngineAvailable(): Boolean = !BuildConfig.ROOT_RUNTIME && runCatching {
        manifest.raw.optJSONObject("directEngine")?.optInt("schema") == 2
    }.getOrDefault(false)

    /**
     * An App-local option, on by default; a running process never changes its execution world.
     * Any explicit choice also forgets an earlier failed Direct launch, so turning it on tries again.
     */
    @Synchronized fun setDirectEngine(enabled: Boolean) {
        check(process == null && phase !in setOf(Phase.STARTING, Phase.RUNNING, Phase.STOPPING, Phase.INSTALLING) && !resetting) { "stop the engine before changing its launch method" }
        check(!enabled || directEngineAvailable()) { "this bundled runtime does not support direct launch" }
        prefs.edit().putBoolean(K_DIRECT_ENGINE, enabled).remove(K_DIRECT_FAILED).apply()
        emitStatus()
    }

    /** While a process is up: its mode; otherwise the mode the next start will use. */
    private fun currentModeSafe(): Boolean {
        val active = process != null || phase == Phase.STARTING || phase == Phase.RUNNING || phase == Phase.STOPPING
        return if (active) runSafe else safeState == SafeState.ON
    }

    /** [dropKept]: give up what a reset kept but cannot be put back (see [KeepFailed]). */
    @Synchronized fun install(dropKept: Boolean = false, onDone: (Throwable?) -> Unit) {
        if (phase == Phase.INSTALLING || resetting) {
            onDone(IllegalStateException("install busy"))
            return
        }
        if (installer.isCurrent() && phase != Phase.ERROR) {
            if (phase == Phase.NOT_INSTALLED) setPhase(Phase.INSTALLED)
            onDone(null)
            return
        }
        val upgrade = installer.installedVersion() != null
        val ticket = rootStartupGate.begin(RootStartupGate.Kind.INSTALL)
        val request = PendingInstall(ticket, dropKept, true, onDone)
        pendingInstall = request
        setPhase(Phase.INSTALLING)
        prepareRootRun(ticket) { accepted ->
            if (!accepted) {
                if (pendingInstall?.ticket == ticket) pendingInstall = null
                if (rootStartupGate.current(ticket)) {
                    error = "root unavailable"
                    setPhase(Phase.ERROR)
                }
                request.finish(IllegalStateException(if (rootStartupGate.current(ticket)) "root unavailable" else "cancelled"))
                return@prepareRootRun
            }
            runCatching {
                rootStartupGate.dispatch(ticket) {
                    val intent = Intent(app, DshService::class.java).setAction(DshService.ACTION_INSTALL)
                        .putExtra("upgrade", upgrade).putExtra(DshService.EXTRA_TICKET, ticket.id)
                    sendForegroundService(intent)
                }
            }.onFailure { failure ->
                if (pendingInstall?.ticket == ticket) pendingInstall = null
                error = "install failed: ${failure.message}"
                setPhase(Phase.ERROR)
                rootStartupGate.invalidate()
                request.finish(failure)
            }
        }
    }

    internal fun acceptService(ticket: Long, install: Boolean): Boolean {
        if (!rootStartupGate.consume(ticket, if (install) RootStartupGate.Kind.INSTALL else RootStartupGate.Kind.START)) return false
        if (BuildConfig.ROOT_RUNTIME && !io.github.ccch1mneyyy.dsh_app.notify.NotifyPostGate.ready(app)) {
            error = "notification permission unavailable"
            setPhase(Phase.ERROR)
            val request = pendingInstall?.takeIf { it.ticket.id == ticket }
            if (request != null) pendingInstall = null
            request?.finish(IllegalStateException(error))
            return false
        }
        return true
    }

    private fun cancelPendingInstall() {
        val request = pendingInstall ?: return
        pendingInstall = null
        request.finish(IllegalStateException("cancelled"))
    }

    /** Called only after the matching service has entered foreground and acquired its wake lock. */
    internal fun installServiceReady(id: Long) {
        val request = pendingInstall?.takeIf { it.ticket.id == id } ?: return
        pendingInstall = null
        startInstall(request)
    }

    private fun startInstall(request: PendingInstall) {
        worker.execute {
            var installError: Throwable? = null
            try {
                check(rootStartupGate.current(request.ticket)) { "cancelled" }
                if (request.unpack) {
                    log("[dsh-app] unpacking runtime ${manifest.runtimeVersion} (${manifest.abi})", "system")
                    val started = System.currentTimeMillis()
                    installer.install(request.dropKept) { done, total ->
                        check(rootStartupGate.current(request.ticket)) { "cancelled" }
                        emit(mapOf("type" to "progress", "step" to "extract", "done" to done, "total" to total))
                    }
                    log("[dsh-app] runtime ready in ${(System.currentTimeMillis() - started) / 1000}s", "system")
                }
                if (rootStartupGate.current(request.ticket)) {
                    error = null
                    strikes = 0
                    failure = null
                    prefs.edit().putInt(K_STRIKES, 0).remove(K_FAILURE).apply()
                    setPhase(Phase.INSTALLED)
                }
            } catch (e: Throwable) {
                installError = e
                if (rootStartupGate.current(request.ticket)) {
                    error = "install failed: ${e.message}"
                    log("[dsh-app] $error", "system")
                    setPhase(Phase.ERROR)
                }
            }
            val outcome = installError
            main.post {
                // This request reached foreground; only its service may finish its notification.
                app.startService(Intent(app, DshService::class.java).setAction(DshService.ACTION_INSTALL_DONE)
                    .putExtra(DshService.EXTRA_TICKET, request.ticket.id)
                    .putExtra(DshService.EXTRA_INSTALL_OK, outcome == null))
                request.finish(outcome)
            }
        }
    }

    fun start(
        env: Map<String, String>,
        phoneStorage: Boolean = false,
        mode: String? = null,
        visualReplies: Boolean = pendingVisualReplies,
    ): Long? {
        pendingPhoneStorage = phoneStorage
        pendingVisualReplies = visualReplies
        if (phase in setOf(Phase.RUNNING, Phase.STARTING, Phase.INSTALLING, Phase.STOPPING) || resetting) return null
        pendingEnv = env
        val next = SafePolicy.onStartRequest(safeState, mode)
        if (next != safeState) setSafeState(next, if (next == SafeState.ON) SafePolicy.REASON_MANUAL else safeReason)
        val ticket = rootStartupGate.begin(RootStartupGate.Kind.START)
        activeStartTicket = ticket.id
        setPhase(Phase.STARTING)
        prepareRootRun(ticket) { accepted ->
            if (!accepted) {
                if (rootStartupGate.current(ticket)) {
                    error = "root unavailable"
                    setPhase(Phase.ERROR)
                }
                return@prepareRootRun
            }
            runCatching {
                rootStartupGate.dispatch(ticket) {
                    sendForegroundService(Intent(app, DshService::class.java).setAction(DshService.ACTION_START)
                        .putExtra(DshService.EXTRA_TICKET, ticket.id))
                }
            }.onFailure {
                error = "start failed: ${it.message}"
                setPhase(Phase.ERROR)
            }
        }
        return ticket.id
    }

    private fun sendForegroundService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
    }

    private fun prepareRootRun(ticket: RootStartupGate.Ticket, ready: (Boolean) -> Unit) {
        if (!BuildConfig.ROOT_RUNTIME) {
            rootStartupGate.prepare(ticket, { it() }, { it() }, { true }, ready)
            return
        }
        rootStartupGate.prepare(ticket,
            execute = { action -> Thread({ action() }, "dsh-root-startup").start() },
            deliver = { action -> main.post { action() } },
            preparation = { current -> PhoneControl.get(app).background.prepareRootForStartup(current) != null },
            ready = ready,
        )
    }

    fun ensureRootAccess(onDone: (code: String?, reason: String?, missing: List<String>) -> Unit) {
        PhoneControl.get(app).background.ensureRootAccessForStartup { code, reason, missing ->
            main.post { onDone(code, reason, missing) }
        }
    }

    /**
     * Writes the app's diagnostics snapshot where the engine's agent can read it
     * (`$DSH_HOME/dsh-app/diagnostics.md`). A few KB on the platform thread, after the
     * engine came up or when the person asks from 设置 → 诊断. Null when the runtime is
     * not installed or the write failed.
     */
    fun publishDiagnostics(markdown: String): Map<String, Any?>? = try {
        if (!installer.isCurrent()) null
        else {
            val file = GuestSetup.writeDiagnostics(installer.rootfs, markdown)
            mapOf("path" to file.absolutePath, "bytes" to file.length())
        }
    } catch (_: Exception) {
        null
    }

    fun stop(onDone: (Throwable?) -> Unit) {
        requestStop(onDone, startTicketOwner = null)
    }

    fun stopOwnedStart(ticketId: Long, onDone: (Throwable?) -> Unit) {
        val ticket = RootStartupGate.Ticket(ticketId, RootStartupGate.Kind.START)
        if (activeStartTicket != ticketId || !rootStartupGate.invalidate(ticket)) {
            onDone(IllegalStateException("start request is no longer current"))
            return
        }
        requestStop(onDone, startTicketOwner = ticketId, gateInvalidated = true)
    }

    private fun requestStop(
        onDone: (Throwable?) -> Unit,
        startTicketOwner: Long?,
        gateInvalidated: Boolean = false,
    ) {
        val requestId = stopRequestIds.incrementAndGet()
        val generation = runGen
        stopRequests[requestId] = StopRequest(generation, startTicketOwner, onDone)
        try {
            stopGen = generation
            managedSkillGate.stop(generation)
            if (!gateInvalidated) rootStartupGate.invalidate()
            if (phase == Phase.INSTALLING) {
                setPhase(if (installer.isCurrent()) Phase.INSTALLED else Phase.NOT_INSTALLED)
            }
            cancelPendingInstall()
            Thread({ PhoneControl.get(app).background.cancelUnwantedPending() }, "dsh-root-cancel").start()
            val intent = Intent(app, DshService::class.java).setAction(DshService.ACTION_STOP)
                .putExtra(DshService.EXTRA_STOP_REQUEST, requestId)
                .putExtra(DshService.EXTRA_STOP_GENERATION, generation)
            if (app.startService(intent) == null) {
                finishStopRequest(requestId, IllegalStateException("runtime service did not accept stop"))
            }
        } catch (error: Throwable) {
            finishStopRequest(requestId, error)
        }
    }

    private fun finishStopRequest(requestId: Long, error: Throwable?) {
        if (requestId == 0L) return
        stopRequests.remove(requestId)?.finish(error)
    }

    /** Private bundled-skill transaction; never falls back to a different executor/root. */
    internal fun installManagedSkill(owner: ManagedSkillOwner, raw: Any?, onDone: (Map<String, Any?>) -> Unit) {
        val request = ManagedSkillRequest.parse(raw)
        val run = request?.let { managedSkillGate.capture(owner, it.expectedToken, it.expectedGeneration) }
        if (request == null || run == null) {
            onDone(mapOf("result" to null, "current" to false, "reason" to if (request == null) "invalid" else "stale"))
            return
        }
        managedSkills.submit(owner, request, installer.rootfs, current = {
            managedSkillGate.current(run) && phase == Phase.RUNNING && runGen == run.runtimeGeneration &&
                stopGen < run.runtimeGeneration && !resetting && token == run.token
        }, onDone = onDone)
    }

    internal fun cancelManagedSkill(owner: ManagedSkillOwner, requestId: String? = null) {
        managedSkills.cancel(owner, requestId)
    }

    internal fun attachManagedSkillOwner(): ManagedSkillOwner {
        val owner = ManagedSkillOwner()
        // Gate attachment and the last unretired real ready share a short lock.
        // Do not replay a phase/token observation across onReady or retirement.
        managedSkillGate.attach(owner)?.let { managedSkills.cancel(it) }
        return owner
    }

    internal fun detachManagedSkillOwner(owner: ManagedSkillOwner) {
        managedSkillGate.detach(owner)
        managedSkills.cancel(owner)
    }

    @Synchronized internal fun startProcess() {
        if (process != null || phase == Phase.INSTALLING || phase == Phase.STOPPING) return
        pendingRootCleanup?.let { pending ->
            if (pending.rootCleanupConfirmed) {
                if (pendingRootCleanup === pending) pendingRootCleanup = null
            } else {
                error = "previous root container cleanup is unconfirmed; stop or reset to retry cleanup"
                log("[dsh-app] $error", "system")
                setPhase(Phase.ERROR)
                DshService.stopSelfIfRunning(app)
                return
            }
        }
        lastLaunch = null
        if (!installer.isCurrent()) {
            error = "runtime not installed"
            setPhase(Phase.ERROR)
            DshService.stopSelfIfRunning(app)
            return
        }
        baseUrl = null
        token = null
        error = null
        val safe = safeState == SafeState.ON
        runSafe = safe
        val gen = ++runGen
        readyAt = null
        healthyAt = null
        synchronized(runTail) { runTail.clear() }
        setPhase(Phase.STARTING)
        timeline.requested()
        worker.execute {
            var launched: DshProcess? = null
            try {
                noteDns(GuestSetup.writeResolvConf(app, installer.rootfs), DnsConfigSource.START)
                val storage = pendingPhoneStorage && PhoneStorage.isGranted(app) && PhoneStorage.binds().isNotEmpty()
                if (!PhoneStorage.syncShortcut(installer.rootfs, storage)) {
                    log("[dsh-app] could not ${if (storage) "create" else "remove"} the phone storage shortcut in the guest home; the directory may be root-owned", "system")
                }
                // Phone control: bridge up and guest files written before DSH starts; off in safe mode.
                val phone = PhoneControl.get(app).prepareLaunch(installer.rootfs, safe) { log(it, "system") }
                if (!safe && !SessionSearchGuest.writeFiles(installer.rootfs)) {
                    log("[dsh-app] message search overlay not written; searching matches titles only", "system")
                }
                if (!safe && !AppAuthGuest.writeFiles(app, installer.rootfs)) {
                    log("[dsh-app] subscription sign-in overlay not written; ChatGPT / Claude sign-in is unavailable", "system")
                }
                if (!safe && !SpeechGuest.writeFiles(installer.rootfs)) {
                    log("[dsh-app] voice input overlay not written; the voice model keeps DSH's defaults", "system")
                }
                if (pendingPhoneStorage && !storage) log("[dsh-app] phone storage requested but permission not granted", "system")
                val env = LinkedHashMap<String, String>()
                env["TZ"] = java.util.TimeZone.getDefault().id
                env.putAll(pendingEnv)
                PerformancePolicy.applyEngineEnvironment(app, env, manifest.launchArgv, manifest.launchEnv)
                if (safe) {
                    SafeMode.prepare(installer.rootfs, briefing())
                    env.putAll(SafeMode.GUEST_ENV)
                    bootProfile = null
                    log("[dsh-app] safe mode: booting profile ${SafeMode.PROFILE} rebuilt from the web template, plugins off", "system")
                } else {
                    bootProfile = try {
                        LastKnownGood.read(installer.rootfs)
                    } catch (_: Exception) {
                        null
                    }
                }
                // Stopped (or started again) while this was queued: launching now would leave an orphan.
                if (stopGen >= gen || gen != runGen) {
                    PhoneControl.get(app).onEngineStopped()
                    return@execute
                }
                val p = DshProcess(app, manifest, installer.rootfs, this)
                launched = p
                lastLaunch = p.launchDiagnostics
                process = p
                timeline.spawned()
                val direct = directEngine()
                p.start(env, if (storage) PhoneStorage.binds() else emptyList(), safe, phone, direct.requested, direct.failed) { actualDirect ->
                    GuestSetup.writeAgentsMd(installer.rootfs, storage, phone, pendingVisualReplies, actualDirect, BuildConfig.ROOT_RUNTIME)
                }
                if (phone) PhoneControl.get(app).launched(p.phoneMounted)
            } catch (e: Throwable) {
                // Keep ownership while the old UID sweep runs, including a
                // failure after spawning but before the waiter was allocated.
                launched?.launchDiagnostics?.failed("startFailed")
                launched?.let { p ->
                    p.failStart(e.message ?: e.javaClass.simpleName)
                    val exited = p.awaitExit()
                    if (!exited || !p.rootCleanupConfirmed) retainPendingRootCleanup(p)
                }
                if (gen != runGen) return@execute
                process = null
                PhoneControl.get(app).onEngineStopped()
                if (stopGen >= gen) return@execute
                error = "start failed: ${e.message}"
                log("[dsh-app] $error", "system")
                setPhase(Phase.ERROR)
                DshService.stopSelfIfRunning(app)
            }
        }
    }

    @Synchronized internal fun stopProcess(requestId: Long = 0L, expectedGeneration: Int? = null): Boolean {
        if (requestId != 0L) {
            val request = stopRequests[requestId] ?: return false
            if (!request.belongsTo(runGen)) {
                finishStopRequest(requestId, IllegalStateException("stop request belongs to an earlier runtime"))
                return false
            }
            val owner = request.startTicketOwner
            if (owner != null && !request.belongsToStart(activeStartTicket ?: 0L)) {
                finishStopRequest(requestId, IllegalStateException("start request is no longer current"))
                return false
            }
        }
        if (expectedGeneration != null && expectedGeneration != runGen) {
            if (requestId != 0L) finishStopRequest(requestId, IllegalStateException("stop request belongs to an earlier runtime"))
            return false
        }
        if (requestId == 0L || stopRequests[requestId]?.startTicketOwner == null) {
            rootStartupGate.invalidate()
        }
        if (phase == Phase.INSTALLING) {
            finishStopRequest(requestId, null)
            return true
        }
        stopGen = runGen
        val p = process
        if (p == null) {
            val pending = pendingRootCleanup
            if (pending != null) {
                worker.execute {
                    pending.stop()
                    val stopped = runCatching { pending.awaitExit(15_000) && pending.rootCleanupConfirmed }
                    if (stopped.getOrDefault(false)) {
                        clearPendingRootCleanup(pending)
                        finishStopRequest(requestId, null)
                    } else {
                        log("[dsh-app] previous root cleanup remains unconfirmed; retry stop or reset", "system")
                        finishStopRequest(requestId, stopped.exceptionOrNull() ?: IllegalStateException("runtime stop or root cleanup is unconfirmed"))
                    }
                }
                return true
            }
            if (phase == Phase.STARTING) {
                // The launch is still queued on the worker: finish the stop behind it.
                setPhase(Phase.STOPPING)
                worker.execute {
                    val launched = process
                    if (launched != null) {
                        launched.stop()
                        finishStopRequest(requestId, awaitStopResult(launched))
                    } else {
                        if (!resetting && phase == Phase.STOPPING) setPhase(Phase.INSTALLED)
                        finishStopRequest(requestId, null)
                    }
                }
            } else if (!resetting && phase != Phase.NOT_INSTALLED && phase != Phase.ERROR) {
                // Keep ERROR: the service's own stop after a crash must not hide the failure.
                setPhase(Phase.INSTALLED)
                finishStopRequest(requestId, null)
            } else {
                finishStopRequest(requestId, null)
            }
            return true
        }
        setPhase(Phase.STOPPING)
        p.stop()
        worker.execute { finishStopRequest(requestId, awaitStopResult(p)) }
        return true
    }

    private fun awaitStopResult(process: DshProcess): Throwable? = try {
        if (process.awaitExit()) null else IllegalStateException("runtime stop or root cleanup is unconfirmed")
    } catch (error: Throwable) {
        error
    }

    /** The default network changed: point the guest's resolv.conf at its DNS. */
    fun refreshDns(properties: LinkProperties) {
        val servers = GuestSetup.pickDns(properties.dnsServers)
        worker.execute {
            if ((phase == Phase.RUNNING || phase == Phase.STARTING) && installer.isCurrent()) {
                noteDns(GuestSetup.writeResolvConf(installer.rootfs, servers), DnsConfigSource.NETWORK)
            }
        }
    }

    private fun noteDns(report: DnsConfigReport, source: DnsConfigSource) {
        dnsLog.line(report, source, System.currentTimeMillis(), SystemClock.elapsedRealtime())?.let { log(it, "system") }
    }

    /** The system blocked or restored this app's network (DshService's default network callback). */
    internal fun onNetworkBlocked(blocked: Boolean, inForeground: Boolean) {
        val line = networkAccess.onBlocked(blocked, inForeground) ?: return
        log(line, "system")
        emitStatus()
    }

    /** DshService stopped watching the network. */
    internal fun onNetworkWatchStopped() = networkAccess.reset()

    /** A line of the app's own into the runtime log. */
    internal fun note(line: String) = log(line, "system")

    /**
     * Stops DSH and waits for it to exit, then deletes the runtime with everything in /root except
     * [keep], which the next install puts back.
     */
    fun reset(keep: Set<ResetKeep> = emptySet(), onDone: (Throwable?) -> Unit) {
        stopGen = runGen
        rootStartupGate.invalidate()
        resetting = true
        cancelPendingInstall()
        Thread({ PhoneControl.get(app).background.cancelUnwantedPending() }, "dsh-root-cancel").start()
        setPhase(Phase.STOPPING)
        worker.execute {
            try {
                check(retryPendingRootCleanup()) { "previous root cleanup is unconfirmed; retry reset after cleanup" }
                process?.let { p ->
                    p.stop()
                    check(p.awaitExit(15_000)) { "runtime stop or root cleanup is unconfirmed; retry reset after cleanup" }
                }
                check(retryPendingRootCleanup()) { "previous root cleanup is unconfirmed; retry reset after cleanup" }
                lastLaunch = null
                try {
                    check(runtimeLogs.pauseDisk()) { "log reset pause timed out" }
                    installer.reset(keep.flatMap { it.paths })
                } finally {
                    runtimeLogs.resumeDisk()
                }
                if (keep.isNotEmpty()) log("[dsh-app] reset keeps ${keep.joinToString { it.wire }}", "system")
                baseUrl = null
                token = null
                error = null
                // A fresh start: even kept plugins get a clean slate (auto safe mode still guards it).
                strikes = 0
                failure = null
                mainFailure = null
                safeState = SafeState.OFF
                safeReason = null
                prefs.edit().remove(K_SAFE_STATE).remove(K_SAFE_REASON).remove(K_STRIKES).remove(K_FAILURE)
                    .remove(K_MAIN_FAILURE).remove(K_MOVED).apply()
                setPhase(Phase.NOT_INSTALLED)
                main.post { onDone(null) }
            } catch (e: Throwable) {
                log("[dsh-app] reset failed: ${e.message}", "system")
                // Still stopping while the old process hasn't exited (its exit report moves the
                // phase on); otherwise the runtime is either still whole or gone.
                setPhase(when {
                    process != null -> Phase.STOPPING
                    runCatching { installer.isCurrent() }.getOrDefault(false) -> Phase.INSTALLED
                    else -> Phase.NOT_INSTALLED
                })
                main.post { onDone(e) }
            } finally {
                resetting = false
            }
        }
    }

    fun recentLogs(lines: Int): List<String> = runtimeLogs.recent(lines)
    fun logSnapshot(lines: Int): Map<String, Any?> = runtimeLogs.snapshot(lines).toMap()

    // ── Safe mode / last known good (called by RuntimePlugin) ───────────────────────

    fun autoSafe(): Boolean = prefs.getBoolean(K_AUTO_SAFE, true)

    fun setAutoSafe(enabled: Boolean) {
        prefs.edit().putBoolean(K_AUTO_SAFE, enabled).apply()
        emitStatus()
    }

    /** Hides the failure card; the strike count stays. */
    fun clearFailure() {
        failure = null
        prefs.edit().remove(K_FAILURE).apply()
        emitStatus()
    }

    /** Output around the failure on the card, else around the main profile's last failure (tokens masked). */
    fun failureLog(): List<String> {
        val name = when {
            failure != null -> LATEST_FAILURE_LOG
            mainFailure != null -> MAIN_FAILURE_LOG
            else -> return emptyList()
        }
        return try {
            File(logDir, name).readLines()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** What [restoreLastKnownGood] would change: `{available, at, disable, enable, changed, patchChanged, empty}`. */
    fun lastKnownGoodStatus(onDone: (Map<String, Any?>) -> Unit) {
        worker.execute {
            val result: Map<String, Any?> = try {
                val lkg = LastKnownGood.load(lkgFile)
                val current = LastKnownGood.read(installer.rootfs)
                if (lkg == null || current == null) {
                    mapOf("available" to false, "at" to lkg?.at)
                } else {
                    LkgPlan.of(lkg.snapshot, current).toMap() + mapOf("available" to true, "at" to lkg.at)
                }
            } catch (e: Exception) {
                mapOf("available" to false, "error" to e.message)
            }
            main.post { onDone(result) }
        }
    }

    /** Puts the main profile's bundle list and settings layer back to the last good run's. */
    fun restoreLastKnownGood(onDone: (Map<String, Any?>?, Throwable?) -> Unit) {
        if (!runSafe && (phase == Phase.RUNNING || phase == Phase.STARTING)) {
            return onDone(null, IllegalStateException(AppStrings.get(app, R.string.safe_stop_engine_first)))
        }
        worker.execute {
            try {
                val lkg = LastKnownGood.load(lkgFile) ?: throw IllegalStateException(AppStrings.get(app, R.string.safe_no_good_config))
                val plan = LastKnownGood.restore(installer.rootfs, lkg.snapshot, SafeMode.stamp(),
                    AppStrings.get(app, R.string.safe_config_missing), AppStrings.get(app, R.string.safe_config_busy))
                log(
                    "[dsh-app] restored the last good plugin set from ${SafeMode.formatTime(lkg.at)}: " +
                        "disabled ${plan.disable}, enabled ${plan.enable}, settings layer ${if (plan.patchChanged) "restored" else "unchanged"}",
                    "system",
                )
                main.post { onDone(plan.toMap(), null) }
            } catch (e: Throwable) {
                log("[dsh-app] restoring the last good plugin set failed: ${e.message}", "system")
                main.post { onDone(null, e) }
            }
        }
    }

    /** Home-level files that would also break the safe profile. */
    fun safePreflight(onDone: (List<Map<String, Any?>>) -> Unit) {
        worker.execute {
            val hazards = try {
                SafeMode.preflight(installer.rootfs, AppStrings.get(app, R.string.safe_patch_hazard)) { names ->
                    AppStrings.get(app, R.string.safe_env_hazard, names.joinToString(if (AppStrings.isEnglish(app)) ", " else "、"))
                }.map { it.toMap() }
            } catch (_: Exception) {
                emptyList()
            }
            main.post { onDone(hazards) }
        }
    }

    /** Renames the given preflight files to `.off-<time>`; the briefing tells the agent about it. */
    fun moveAside(paths: List<String>, onDone: (List<Map<String, Any?>>?, Throwable?) -> Unit) {
        worker.execute {
            try {
                val moved = SafeMode.moveAside(installer.rootfs, paths, SafeMode.stamp())
                if (moved.isNotEmpty()) {
                    val record = JSONArray(movedAside() + moved.map { (from, to) -> "$from → $to" })
                    prefs.edit().putString(K_MOVED, record.toString()).apply()
                    moved.forEach { (from, to) -> log("[dsh-app] moved $from aside to $to", "system") }
                }
                main.post { onDone(moved.map { (from, to) -> mapOf("from" to from, "to" to to) }, null) }
            } catch (e: Throwable) {
                main.post { onDone(null, e) }
            }
        }
    }

    private fun movedAside(): List<String> = try {
        val array = JSONArray(prefs.getString(K_MOVED, null) ?: "[]")
        List(array.length()) { array.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun briefing(): SafeMode.Briefing {
        val lkg = LastKnownGood.load(lkgFile)
        val log = try {
            File(logDir, MAIN_FAILURE_LOG).readLines()
        } catch (_: Exception) {
            emptyList()
        }
        val mainFailure = mainFailure
        return SafeMode.Briefing(
            failure = mainFailure,
            reason = safeReason,
            moved = movedAside(),
            lkgAt = lkg?.at,
            lkgBundles = lkg?.snapshot?.bundles ?: emptyList(),
            lkgVersions = lkg?.snapshot?.versions ?: emptyMap(),
            failureLog = if (mainFailure == null) emptyList() else log,
        )
    }

    private fun setSafeState(next: SafeState, reason: String?) {
        safeState = next
        safeReason = if (next == SafeState.OFF) null else reason
        val edit = prefs.edit().putString(K_SAFE_STATE, next.wire).putString(K_SAFE_REASON, safeReason)
        if (next == SafeState.OFF) edit.remove(K_MOVED)
        edit.apply()
    }

    /** Called two minutes after the login line if the same run is still up. */
    private fun onHealthy() {
        healthyAt = null
        if (runSafe) return
        val recovered = safeState == SafeState.PROBATION
        strikes = 0
        failure = null
        mainFailure = null
        prefs.edit().putInt(K_STRIKES, 0).remove(K_FAILURE).remove(K_MAIN_FAILURE).apply()
        if (safeState != SafeState.OFF) setSafeState(SafeState.OFF, null)
        val booted = bootProfile
        worker.execute {
            try {
                if (LastKnownGood.pinBooted(booted) { LastKnownGood.save(lkgFile, it, System.currentTimeMillis()) }) {
                    log("[dsh-app] DSH has been up for 2 minutes; recorded its plugin set as the last good one", "system")
                }
            } catch (e: Exception) {
                log("[dsh-app] could not record the last good plugin set: ${e.message}", "system")
            }
        }
        if (recovered) notice(AppStrings.get(app, R.string.safe_normal_recovered))
        emitStatus()
    }

    /** Bundles enabled or upgraded since the last good run: the first suspects of a new failure. */
    private fun recentChanges(): List<String> = try {
        val lkg = LastKnownGood.load(lkgFile)
        val current = bootProfile ?: LastKnownGood.read(installer.rootfs)
        if (lkg == null || current == null) {
            emptyList()
        } else {
            val plan = LkgPlan.of(lkg.snapshot, current)
            (plan.disable + plan.changed).distinct()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun recordFailure(f: RunFailure, tail: List<String>) {
        failure = f
        val json = JSONObject(f.toMap()).toString()
        val edit = prefs.edit().putString(K_FAILURE, json)
        // A later kill from outside says nothing about the profile: keep the plugin failure it followed.
        val main = !f.safeMode && (f.kind != FailureKind.KILLED || mainFailure == null)
        if (main) {
            mainFailure = f
            edit.putString(K_MAIN_FAILURE, json)
        }
        edit.apply()
        try {
            logDir.mkdirs()
            val text = tail.joinToString("\n", postfix = "\n") { CrashClassifier.redact(it) }
            File(logDir, LATEST_FAILURE_LOG).writeText(text)
            if (main) File(logDir, MAIN_FAILURE_LOG).writeText(text)
        } catch (_: Exception) {
        }
    }

    private fun readFailure(key: String): RunFailure? = try {
        prefs.getString(key, null)?.let { text ->
            val json = JSONObject(text)
            val kind = FailureKind.parse(json.optString("kind")) ?: return@let null
            fun list(name: String): List<String> {
                val array = json.optJSONArray(name) ?: return emptyList()
                return List(array.length()) { array.optString(it) }
            }
            RunFailure(
                kind = kind,
                exitCode = json.optInt("exitCode"),
                at = json.optLong("at"),
                safeMode = json.optBoolean("safeMode"),
                summary = if (json.isNull("summary")) null else json.optString("summary"),
                requiredRows = list("requiredRows"),
                suspects = list("suspects"),
                recent = list("recent"),
                diagnostics = if (json.isNull("diagnostics")) null else json.optString("diagnostics"),
                upSeconds = if (json.isNull("upSeconds")) null else json.optLong("upSeconds"),
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun notice(text: String) = emit(mapOf("type" to "notice", "text" to text))

    // DshProcess.Listener

    override fun onLog(line: String, stream: String) {
        if (stream != "system") {
            synchronized(runTail) {
                runTail.addLast(line)
                while (runTail.size > CrashClassifier.TAIL_LINES) runTail.removeFirst()
            }
        }
        log(line, stream)
    }

    override fun onTidying(active: Boolean) {
        if (tidyingFiles == active) return
        tidyingFiles = active
        emitStatus()
    }

    @Synchronized override fun onReady(source: DshProcess, baseUrl: String, token: String) {
        // A late report from a stopped or replaced process.
        if (!acceptsRunReady(source === process, phase, runGen, stopGen, resetting)) return
        DshService.cancelStoppedAlert(app)
        this.baseUrl = baseUrl
        this.token = token
        if ((phase == Phase.STARTING || phase == Phase.RUNNING) &&
            stopGen < runGen && !resetting) {
            managedSkillGate.ready(runGen, token)
        }
        readyAt = SystemClock.elapsedRealtime()
        if (failure != null) {
            // This run got going, so the previous failure card is stale; strikes reset only when healthy.
            failure = null
            prefs.edit().remove(K_FAILURE).apply()
        }
        if (!runSafe) {
            // First boot creates the profile after startProcess reads it. Capture it before
            // RUNNING lets the app change it; a later disk read would not describe this boot.
            bootProfile = LastKnownGood.bootedAtReady(bootProfile) { LastKnownGood.read(installer.rootfs) }
        }
        if (!runSafe) healthyAt = System.currentTimeMillis() + SafePolicy.HEALTHY_MS
        log("[dsh-app] DSH is listening at $baseUrl${if (runSafe) " (safe mode)" else ""}", "system")
        timeline.ready()?.let { log(it, "system") }
        setPhase(Phase.RUNNING)
        PhoneControl.get(app).onEngineReady()
        val gen = runGen
        main.postDelayed({ if (gen == runGen && phase == Phase.RUNNING && process != null) onHealthy() }, SafePolicy.HEALTHY_MS)
    }

    @Synchronized override fun onExit(source: DshProcess, code: Int, expected: Boolean) {
        if (source !== process) return
        if (BuildConfig.ROOT_RUNTIME) {
            if (source.rootCleanupConfirmed) {
                clearPendingRootCleanup(source)
            } else {
                retainPendingRootCleanup(source)
            }
        }
        try {
            if (!expected && stopGen < runGen) {
                source.launchDiagnostics.failed(if (source.startupError != null) "startFailed" else "exit:$code")
            }
            process = null
            PhoneControl.get(app).onEngineStopped()
            baseUrl = null
            token = null
            healthyAt = null
            log("[dsh-app] DSH exited with code $code", "system")
            if (expected || stopGen >= runGen) {
                if (!resetting && phase == Phase.STOPPING) setPhase(Phase.INSTALLED)
                DshService.stopSelfIfRunning(app)
                return
            }
            source.startupError?.let {
                // A local startup failure is neither a user stop nor proof of a
                // plugin failure; do not count it toward automatic safe mode.
                error = "start failed: $it"
                setPhase(Phase.ERROR)
                DshService.postStoppedAlert(app)
                DshService.stopSelfIfRunning(app)
                return
            }
            val tail = synchronized(runTail) { runTail.toList() }
            var f = CrashClassifier.classify(code, readyAt, SystemClock.elapsedRealtime(), System.currentTimeMillis(), tail, runSafe, source.direct)
            if (SafePolicy.directLaunchFailed(f, source.direct)) {
                // Direct can't launch on this device: PRoot from now on with this runtime, no failure card,
                // no strike. A new runtime, or the user turning Direct on again, gives it another try.
                prefs.edit().putString(K_DIRECT_FAILED, manifest.runtimeVersion).apply()
                log("[dsh-app] direct launch failed (${f.summary}); starting again with compatible launch", "system")
                restartNow("with compatible launch")
                return
            }
            if (!runSafe) f = f.copy(recent = recentChanges())
            val outcome = SafePolicy.onFailure(safeState, strikes, f, autoSafe(),
                AppStrings.get(app, R.string.safe_probation_failed), AppStrings.get(app, R.string.safe_auto_started))
            val now = SystemClock.elapsedRealtime()
            val delay = SafePolicy.autoRestartDelay(f.kind, code, readyAt?.let { now - it }, autoRestarts.toList(), now)
            if (!outcome.restartSafe && delay != null && DshService.isRunning) {
                // Killed from outside after a good run (memory pressure, a phantom-process sweep): try again.
                autoRestarts.removeAll { now - it >= 600_000L }
                autoRestarts.addLast(now)
                val gen = runGen
                setPhase(Phase.STARTING)
                log("[dsh-app] killed from outside; restarting in ${delay / 1000}s", "system")
                main.postDelayed({
                    if (gen == runGen && stopGen < gen && phase == Phase.STARTING && process == null && DshService.isRunning) {
                        startProcess()
                    }
                }, delay)
                return
            }
            recordFailure(f, tail)
            strikes = outcome.strikes
            prefs.edit().putInt(K_STRIKES, strikes).apply()
            if (outcome.state != safeState) setSafeState(outcome.state, outcome.reason ?: safeReason)
            log("[dsh-app] classified as ${f.kind.wire}${if (f.safeMode) " (safe mode)" else ""}; consecutive failures: $strikes", "system")
            error = "DSH exited unexpectedly (code $code)"
            if (outcome.restartSafe) {
                log("[dsh-app] restarting in safe mode", "system")
                outcome.notice?.let { notice(it) }
                restartNow("in safe mode")
            } else {
                setPhase(Phase.ERROR)
                DshService.postStoppedAlert(app)
                DshService.stopSelfIfRunning(app)
            }
        } finally {
            runtimeLogs.flush()
        }
    }

    @Synchronized private fun retainPendingRootCleanup(source: DshProcess) {
        if (BuildConfig.ROOT_RUNTIME && !source.rootCleanupConfirmed && pendingRootCleanup == null) {
            pendingRootCleanup = source
        }
    }

    @Synchronized private fun clearPendingRootCleanup(source: DshProcess) {
        pendingRootCleanup = clearPendingIfSameOwner(
            pendingRootCleanup,
            source,
            source.rootCleanupConfirmed,
        )
    }

    private fun retryPendingRootCleanup(): Boolean {
        val pending = synchronized(this) { pendingRootCleanup } ?: return true
        if (!pending.rootCleanupConfirmed) {
            pending.stop()
            if (!pending.awaitExit(15_000)) return false
        }
        if (!pending.rootCleanupConfirmed) return false
        clearPendingRootCleanup(pending)
        return true
    }

    /** Starts again right away; [startProcess] picks the mode. [how] names it if that fails. */
    private fun restartNow(how: String) {
        val gen = runGen
        main.post {
            if (gen != runGen || stopGen >= gen) return@post
            if (DshService.isRunning) {
                startProcess()
            } else {
                phase = Phase.INSTALLED
                try {
                    start(pendingEnv, pendingPhoneStorage)
                } catch (e: Exception) {
                    log("[dsh-app] could not restart $how: ${e.message}", "system")
                    setPhase(Phase.ERROR)
                }
            }
        }
    }

    private fun log(line: String, stream: String) = runtimeLogs.accept(line, stream)

    private fun setPhase(next: Phase) {
        if (next != Phase.RUNNING) managedSkillGate.retire()
        phase = next
        emitStatus()
        DshService.updateNotification(app)
    }

    private fun emitStatus() = emit(mapOf("type" to "status", "status" to status()))

    private fun emit(event: Map<String, Any?>) {
        val targets = synchronized(sinks) { sinks.toList() }
        if (targets.isEmpty()) return
        main.post { targets.forEach { it.emit(event) } }
    }

    companion object {
        private const val PREFS = "dsh_runtime"
        private const val K_SAFE_STATE = "safeState"
        private const val K_SAFE_REASON = "safeReason"
        private const val K_STRIKES = "strikes"
        private const val K_FAILURE = "failure"
        private const val K_MAIN_FAILURE = "mainFailure"
        private const val K_MOVED = "movedAside"
        private const val K_AUTO_SAFE = "autoSafe"
        private const val K_DIRECT_ENGINE = "directEngine"
        /** Runtime version Direct failed to launch with (see [SafePolicy.directLaunchFailed]). */
        private const val K_DIRECT_FAILED = "directEngineFailedRuntime"
        private const val MAIN_FAILURE_LOG = "failure-normal.log"
        private const val LATEST_FAILURE_LOG = "failure-latest.log"

        @Volatile private var instance: RuntimeController? = null

        fun get(context: Context): RuntimeController =
            instance ?: synchronized(this) {
                instance ?: RuntimeController(context.applicationContext).also { instance = it }
            }
    }
}

internal fun <T : Any> clearPendingIfSameOwner(pending: T?, completed: T, confirmed: Boolean): T? =
    if (pending === completed && confirmed) null else pending
