package io.github.ardaulas.earshot.app

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ardaulas.earshot.car.CarIds
import io.github.ardaulas.earshot.car.CarPropertyGateway
import io.github.ardaulas.earshot.car.PlatformCar
import io.github.ardaulas.earshot.core.interpret.LmInterpreter
import io.github.ardaulas.earshot.core.interpret.LmWireFormat
import io.github.ardaulas.earshot.core.interpret.RuleInterpreter
import io.github.ardaulas.earshot.core.model.AssistantStatus
import io.github.ardaulas.earshot.core.model.ModelGate
import io.github.ardaulas.earshot.core.model.ModelManifest
import io.github.ardaulas.earshot.core.policy.DrivingState
import io.github.ardaulas.earshot.core.policy.Policy
import io.github.ardaulas.earshot.core.policy.withUxRestrictions
import io.github.ardaulas.earshot.core.speech.WavReader
import io.github.ardaulas.earshot.core.time.MonotonicClock
import io.github.ardaulas.earshot.core.trace.HostInfo
import io.github.ardaulas.earshot.core.trace.InputSource
import io.github.ardaulas.earshot.core.trace.JsonlTraceWriter
import io.github.ardaulas.earshot.core.trace.TurnTrace
import io.github.ardaulas.earshot.core.turn.ScreenContent
import io.github.ardaulas.earshot.core.turn.TurnEngine
import io.github.ardaulas.earshot.core.vehicle.ClimateProperty
import io.github.ardaulas.earshot.core.vehicle.DrivingScenario
import io.github.ardaulas.earshot.core.vehicle.DrivingStateResolver
import io.github.ardaulas.earshot.core.vehicle.Gear
import io.github.ardaulas.earshot.core.vehicle.ReadResult
import io.github.ardaulas.earshot.core.vehicle.SimulatedVehicleGateway
import io.github.ardaulas.earshot.core.vehicle.VehicleGateway
import io.github.ardaulas.earshot.llama.LlamaLmEngine
import io.github.ardaulas.earshot.whisper.WhisperSpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

sealed interface ModelStatus {
    data object Checking : ModelStatus

    data class Disabled(
        val reason: String,
    ) : ModelStatus

    data class Ready(
        val speechModel: String,
        val lmNote: String?,
    ) : ModelStatus
}

data class UiState(
    val models: ModelStatus = ModelStatus.Checking,
    val phase: Phase = Phase.IDLE,
    val drivingState: DrivingState = DrivingState.UNKNOWN,
    val scenario: String = DrivingScenario.PARKED.name,
    val connected: Boolean = true,
    val speedKmh: Double? = null,
    val gear: Gear? = null,
    val climate: Map<ClimateProperty, Int> = SimulatedVehicleGateway.DEFAULT_CLIMATE,
    val screen: ScreenContent? = null,
    val lastSpoken: String? = null,
    val awaitingConfirmation: Boolean = false,
    val lastTrace: TurnTrace? = null,
    val ttsAvailable: Boolean? = null,
    val message: String? = null,
    val clips: List<String> = emptyList(),
    /** Debug builds only: what the playing clip says, captioned for screen recordings (which have no audio). */
    val clipCaption: String? = null,
    /** True on an automotive build with the car service: signals come from the car API. */
    val carApi: Boolean = false,
    /** True when climate writes reach the car (needs CONTROL_CAR_CLIMATE); false means simulated writes. */
    val realClimateWrites: Boolean = false,
    /** The platform's UX restrictions (car only; null on a phone). */
    val uxRestricted: Boolean? = null,
)

/**
 * Owns the pipeline for the single screen. On Android Automotive (the automotive feature present and
 * the car service reachable) speed, gear and UX restrictions come from the car API; climate writes go
 * to the car only with CONTROL_CAR_CLIMATE, otherwise to the simulated vehicle. Elsewhere (a phone)
 * everything is simulated, with scripted driving scenarios, exactly as in v0.2.x.
 */
class AssistantViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val clock = MonotonicClock.System
    private val simulated = SimulatedVehicleGateway(clock, DrivingScenario.PARKED)
    private val automotive = app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private val platformCar: PlatformCar? = if (automotive) PlatformCar.connect(app) else null
    private val carGateway: CarPropertyGateway? =
        platformCar?.let { car ->
            // Real climate writes: the privileged permission, supported properties, and an emulator
            // (the privileged install is an emulator-only test setup; re-audit N5).
            val canWriteClimate =
                ContextCompat.checkSelfPermission(app, PERMISSION_CONTROL_CAR_CLIMATE) == PackageManager.PERMISSION_GRANTED &&
                    car.areaIds(CarIds.HVAC_TEMPERATURE_SET).isNotEmpty() &&
                    isEmulator()
            CarPropertyGateway(car, clock, realClimate = canWriteClimate, simulatedClimate = simulated)
        }

    /**
     * On a phone: the simulated vehicle. On Android Automotive: the car API, or, if the car service
     * cannot be reached, a gateway with no signals and no controls, so the state is unknown (handled
     * as moving) instead of a simulated "parked" (re-audit N2).
     */
    private val vehicle: VehicleGateway =
        carGateway ?: if (automotive) UnavailableVehicleGateway else simulated
    private val resolver = DrivingStateResolver()
    private val speaker = Speaker(app)
    private val capture = AudioCapture(clock)
    private val host =
        HostInfo(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            emulator = isEmulator(),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        )
    private val traces = JsonlTraceWriter(File(app.filesDir, "traces"), { LocalDate.now().toString() })

    private var engine: TurnEngine? = null
    private var speech: WhisperSpeechEngine? = null
    private var llm: LlamaLmEngine? = null
    private var warmup: Job? = null
    private var turnJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch(Dispatchers.Default) { tickDrivingSignals() }
        viewModelScope.launch(Dispatchers.Default) { guardParkedOutput() }
        viewModelScope.launch { speaker.available.collect { a -> _state.update { it.copy(ttsAvailable = a) } } }
        viewModelScope.launch { loadModels() }
        refreshClips()
    }

    /** True while the current reply (screen content or a long answer) is only allowed when parked. */
    @Volatile private var parkedOnlyOutput = false

    /** A confirmation question delivered only on screen; it stops being answerable with the screen. */
    @Volatile private var screenConfirmationId: String? = null

    /** The car is no longer parked: take parked-only content off the screen and stop a long reply (audit #7). */
    private fun revokeParkedOutput() {
        if (_state.value.screen != null) _state.update { it.copy(screen = null) }
        screenConfirmationId?.let { id ->
            screenConfirmationId = null
            engine?.confirmationFailed(id)
        }
        if (parkedOnlyOutput) {
            parkedOnlyOutput = false
            speaker.stop()
        }
    }

    /** The platform's UX restrictions: null on a phone, where there is no restrictions service. */
    private fun uxRestricted(): Boolean? =
        when {
            platformCar != null -> platformCar.requiresDistractionOptimization.value
            automotive -> true
            else -> null
        }

    /** Runs off the main thread: car-service calls can block (re-audit N4). */
    private suspend fun tickDrivingSignals() {
        while (true) {
            carGateway?.poll()
            val signals = vehicle.latestSignals()
            val driving =
                synchronized(resolver) {
                    resolver.update(signals)
                    resolver.current(clock.millis())
                }.withUxRestrictions(uxRestricted())
            if (driving != DrivingState.PARKED) revokeParkedOutput()
            val climate =
                if (carGateway?.realClimate == true) {
                    ClimateProperty.entries.mapNotNull { p -> (vehicle.read(p) as? ReadResult.Value)?.let { p to it.value } }.toMap()
                } else {
                    simulated.snapshot()
                }
            _state.update {
                it.copy(
                    drivingState = driving,
                    speedKmh = signals?.speedKmh,
                    gear = signals?.gear,
                    connected = vehicle.isAvailable,
                    scenario = simulated.currentScenario.name,
                    climate = climate,
                    carApi = carGateway != null,
                    realClimateWrites = carGateway?.realClimate == true,
                    uxRestricted = uxRestricted(),
                    awaitingConfirmation = engine?.isAwaitingConfirmation ?: false,
                )
            }
            delay(TICK_MS)
        }
    }

    /**
     * Parked-only output follows the driving state even when the signal tick is stuck in a car-service
     * call: this loop makes no car call, so a stale reading ages to unknown and the screen is revoked
     * (pre-review F6). UX restrictions are read from their flow, also without a call.
     */
    private suspend fun guardParkedOutput() {
        while (true) {
            val driving = drivingStateNow()
            if (driving != DrivingState.PARKED) revokeParkedOutput()
            if (_state.value.drivingState != driving || _state.value.uxRestricted != uxRestricted()) {
                _state.update { it.copy(drivingState = driving, uxRestricted = uxRestricted()) }
            }
            delay(GUARD_MS)
        }
    }

    /**
     * The state from the background tick's latest reading; no car-service call here. If the tick
     * stalls, the reading ages past 1 s and the state becomes unknown (handled as moving).
     */
    private fun drivingStateNow(): DrivingState =
        synchronized(resolver) {
            resolver.current(clock.millis())
        }.withUxRestrictions(uxRestricted())

    private suspend fun loadModels() {
        val app = getApplication<Application>()
        val status =
            withContext(Dispatchers.IO) {
                val manifest =
                    ModelManifest.parse(
                        app.assets
                            .open("models.json")
                            .bufferedReader()
                            .use { it.readText() },
                    )
                val dir = app.getExternalFilesDir("models") ?: return@withContext AssistantStatus.Disabled("No external files directory.")
                // Load only from a verified app-private snapshot (audit #15).
                ModelGate.check(manifest, dir, File(app.filesDir, "models"))
            }
        when (status) {
            is AssistantStatus.Disabled -> {
                _state.update { it.copy(models = ModelStatus.Disabled(status.reason)) }
            }

            is AssistantStatus.Ready -> {
                // Load outside cancellation and close it if the view model went away meanwhile (re-audit N9).
                val loaded = withContext(NonCancellable + Dispatchers.IO) { WhisperSpeechEngine.load(status.stt) }
                if (!currentCoroutineContext().isActive) {
                    loaded?.close()
                    return
                }
                if (loaded == null) {
                    _state.update { it.copy(models = ModelStatus.Disabled("Speech model ${status.stt.name} could not be loaded.")) }
                    return
                }
                speech = loaded
                val lmFile = status.lm
                val lmEngine = lmFile?.let { withContext(NonCancellable + Dispatchers.IO) { LlamaLmEngine.load(it) } }
                if (!currentCoroutineContext().isActive) {
                    lmEngine?.close()
                    loaded.close()
                    return
                }
                llm = lmEngine
                val lmNote =
                    when {
                        lmFile == null -> "fallback off. ${status.lmProblem}"
                        lmEngine == null -> "fallback off. ${lmFile.name} could not be loaded."
                        else -> "${lmFile.name}, fallback for indirect requests (always confirmed)."
                    }
                val lm = lmEngine?.let { LmInterpreter(it) }
                engine =
                    TurnEngine(
                        speech = loaded,
                        rules = RuleInterpreter(),
                        lm = lm,
                        policy = Policy(),
                        vehicle = vehicle,
                        drivingState = ::drivingStateNow,
                        clock = clock,
                        host = host,
                        traceSink = traces,
                    )
                _state.update { it.copy(models = ModelStatus.Ready(status.stt.name, lmNote)) }
                // Decode the fixed system prompt and examples once, so the first real request is fast.
                lmEngine?.let { engine ->
                    warmup =
                        viewModelScope.launch {
                            runCatching {
                                engine.complete(
                                    LmWireFormat.SYSTEM_PROMPT,
                                    LmWireFormat.EXAMPLES,
                                    "hello",
                                    LmWireFormat.GRAMMAR,
                                    1,
                                    LmWireFormat.ASSISTANT_PREFIX,
                                )
                            }.onFailure { e -> Log.w(TAG, "language-model warm-up failed", e) }
                        }
                }
            }
        }
    }

    /** Push-to-talk pressed. While a turn is being processed, a press cancels it instead. */
    fun onPress(hasMicPermission: Boolean) {
        _state.update { it.copy(clipCaption = null) }
        when (_state.value.phase) {
            Phase.THINKING -> {
                turnJob?.cancel()
                return
            }

            Phase.SPEAKING, Phase.LISTENING -> {
                return
            }

            Phase.IDLE -> {
                Unit
            }
        }
        if (engine == null) return
        if (!hasMicPermission) {
            _state.update { it.copy(message = "Microphone permission is needed for push-to-talk. Nothing is recorded or stored.") }
            return
        }
        if (!capture.start(viewModelScope)) {
            _state.update { it.copy(message = "The microphone is not available.") }
            return
        }
        _state.update { it.copy(phase = Phase.LISTENING, message = null) }
    }

    /** Push-to-talk released. The utterance ends when capture actually stopped (maybe at the limit). */
    fun onRelease() {
        if (_state.value.phase != Phase.LISTENING) return
        runTurn(InputSource.MIC) { capture.stop() }
    }

    /** Whether the activity is started, i.e. whether anything on screen can be seen. */
    @Volatile private var visible = false

    fun onLifecycleStart() {
        visible = true
    }

    /**
     * The activity stopped: never keep the microphone, never act on half an utterance, and drop any
     * question the user may not have heard or seen (audit #10, re-audit #4).
     */
    fun onLifecycleStop() {
        visible = false
        capture.abort()
        turnJob?.cancel()
        speaker.stop()
        engine?.abandonConfirmation()
        _state.update { it.copy(phase = Phase.IDLE, awaitingConfirmation = false) }
    }

    fun playClip(name: String) {
        if (_state.value.phase != Phase.IDLE || engine == null) return
        val file = ClipProvider.clips(getApplication()).firstOrNull { it.name == name } ?: return
        _state.update { it.copy(clipCaption = ClipProvider.caption(getApplication(), name) ?: name) }
        runTurn(InputSource.CLIP) {
            val pcm =
                withContext(Dispatchers.IO) {
                    try {
                        // Bounded read: a clip is at most a few seconds of 16-bit audio.
                        if (file.length() > MAX_CLIP_BYTES) FloatArray(0) else WavReader.read(file.readBytes())
                    } catch (e: IOException) {
                        FloatArray(0)
                    }
                }
            val now = clock.millis()
            Captured(pcm, now, now, overflowed = false)
        }
    }

    private fun runTurn(
        source: InputSource,
        audio: suspend () -> Captured,
    ) {
        val turnEngine = engine ?: return
        _state.update { it.copy(phase = Phase.THINKING, message = null) }
        turnJob =
            viewModelScope.launch {
                try {
                    val captured = audio()
                    if (captured.overflowed) {
                        // Held past the limit: reject the whole utterance rather than act on its start (audit #9).
                        // The rejected utterance may have been an answer: end any pending question too.
                        turnEngine.abandonConfirmation()
                        _state.update { it.copy(phase = Phase.SPEAKING, screen = null, lastSpoken = TOO_LONG) }
                        speaker.speak(TOO_LONG)
                        return@launch
                    }
                    val result = turnEngine.handle(captured.pcm, source, captured.endMs, captured.startMs)
                    _state.update {
                        it.copy(
                            phase = Phase.SPEAKING,
                            screen = result.screen,
                            lastSpoken = result.spoken,
                            awaitingConfirmation = false,
                            lastTrace = result.trace,
                        )
                    }
                    parkedOnlyOutput = result.screen != null || result.spoken.split(" ").size > Policy.MAX_WORDS_WHILE_MOVING
                    // Capture stays off until speech is done (SG-8).
                    val spoken = speaker.speak(result.spoken)
                    result.confirmationId?.let { id -> deliverConfirmation(turnEngine, id, spoken, result.spoken) }
                } finally {
                    _state.update { it.copy(phase = Phase.IDLE, awaitingConfirmation = engine?.isAwaitingConfirmation ?: false) }
                }
            }
    }

    /**
     * A confirmation question counts only once delivered (audit #4): spoken, or, with no speech engine,
     * shown on screen while parked. Otherwise the proposal is dropped and can never be confirmed.
     */
    private fun deliverConfirmation(
        turnEngine: TurnEngine,
        id: String,
        spoken: Boolean,
        question: String,
    ) {
        when {
            spoken -> {
                turnEngine.confirmationDelivered(id)
            }

            visible && drivingStateNow() == DrivingState.PARKED -> {
                _state.update { it.copy(screen = ScreenContent.Text(question)) }
                screenConfirmationId = id
                turnEngine.confirmationDelivered(id)
            }

            else -> {
                turnEngine.confirmationFailed(id)
            }
        }
    }

    fun selectScenario(name: String) {
        DrivingScenario.ALL.firstOrNull { it.name == name }?.let(simulated::play)
    }

    fun setConnected(connected: Boolean) = simulated.setConnected(connected)

    fun refreshClips() {
        _state.update { it.copy(clips = ClipProvider.clips(getApplication()).map { f -> f.name }) }
    }

    override fun onCleared() {
        speaker.shutdown()
        capture.abort()
        // Never block the main thread here (audit #13): the engines' close() is safe while a call is
        // running (it aborts it and the call frees on its way out), and cleanup runs off Main.
        val jobs = listOfNotNull(turnJob, warmup)
        val s = speech
        val l = llm
        val car = platformCar
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            jobs.forEach { it.cancel() }
            s?.close()
            l?.close()
            jobs.forEach { it.join() }
            car?.disconnect()
        }
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.HARDWARE == "ranchu" ||
            Build.HARDWARE == "goldfish" ||
            Build.PRODUCT.contains("sdk")

    private companion object {
        const val TICK_MS = 200L
        const val GUARD_MS = 100L
        const val TAG = "earshot"
        const val MAX_CLIP_BYTES = 2_000_000L
        const val TOO_LONG = "That was too long. Please say it again."
        const val PERMISSION_CONTROL_CAR_CLIMATE = "android.car.permission.CONTROL_CAR_CLIMATE"
    }
}

/** Android Automotive without a reachable car service: no signals, no controls. */
private object UnavailableVehicleGateway : VehicleGateway {
    override val isAvailable = false

    override suspend fun read(property: ClimateProperty) = ReadResult.Unavailable

    override suspend fun write(
        property: ClimateProperty,
        value: Int,
        notAfterMs: Long,
    ) = io.github.ardaulas.earshot.core.vehicle.WriteResult.Unavailable

    override fun latestSignals(): io.github.ardaulas.earshot.core.vehicle.SignalSample? = null
}
