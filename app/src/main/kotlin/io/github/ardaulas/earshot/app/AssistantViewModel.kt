package io.github.ardaulas.earshot.app

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ardaulas.earshot.core.interpret.LmInterpreter
import io.github.ardaulas.earshot.core.interpret.LmWireFormat
import io.github.ardaulas.earshot.core.interpret.RuleInterpreter
import io.github.ardaulas.earshot.core.model.AssistantStatus
import io.github.ardaulas.earshot.core.model.ModelGate
import io.github.ardaulas.earshot.core.model.ModelManifest
import io.github.ardaulas.earshot.core.policy.DrivingState
import io.github.ardaulas.earshot.core.policy.Policy
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
import io.github.ardaulas.earshot.core.vehicle.SimulatedVehicleGateway
import io.github.ardaulas.earshot.llama.LlamaLmEngine
import io.github.ardaulas.earshot.whisper.WhisperSpeechEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
)

/**
 * Owns the pipeline for the single screen. The simulated vehicle and the scripted driving scenarios
 * live here; on the automotive emulator they are replaced by the car property API (next phases).
 */
class AssistantViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val clock = MonotonicClock.System
    private val vehicle = SimulatedVehicleGateway(clock, DrivingScenario.PARKED)
    private val resolver = DrivingStateResolver()
    private val speaker = Speaker(app)
    private val capture = AudioCapture()
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
        viewModelScope.launch { tickDrivingSignals() }
        viewModelScope.launch { speaker.available.collect { a -> _state.update { it.copy(ttsAvailable = a) } } }
        viewModelScope.launch { loadModels() }
        refreshClips()
    }

    private suspend fun tickDrivingSignals() {
        while (true) {
            val signals = vehicle.latestSignals()
            val driving =
                synchronized(resolver) {
                    resolver.update(signals)
                    resolver.current(clock.millis())
                }
            _state.update {
                it.copy(
                    drivingState = driving,
                    speedKmh = signals?.speedKmh,
                    gear = signals?.gear,
                    connected = vehicle.isAvailable,
                    scenario = vehicle.currentScenario.name,
                    climate = vehicle.snapshot(),
                    awaitingConfirmation = engine?.isAwaitingConfirmation ?: false,
                )
            }
            delay(TICK_MS)
        }
    }

    private fun drivingStateNow(): DrivingState =
        synchronized(resolver) {
            resolver.update(vehicle.latestSignals())
            resolver.current(clock.millis())
        }

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
                ModelGate.check(manifest, dir)
            }
        when (status) {
            is AssistantStatus.Disabled -> {
                _state.update { it.copy(models = ModelStatus.Disabled(status.reason)) }
            }

            is AssistantStatus.Ready -> {
                val loaded = withContext(Dispatchers.IO) { WhisperSpeechEngine.load(status.stt) }
                if (loaded == null) {
                    _state.update { it.copy(models = ModelStatus.Disabled("Speech model ${status.stt.name} could not be loaded.")) }
                    return
                }
                speech = loaded
                val lmFile = status.lm
                val lmEngine = lmFile?.let { withContext(Dispatchers.IO) { LlamaLmEngine.load(it) } }
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

    /** Push-to-talk released: the utterance ends now. */
    fun onRelease() {
        if (_state.value.phase != Phase.LISTENING) return
        val endMs = clock.millis()
        runTurn(InputSource.MIC, endMs) { capture.stop() }
    }

    fun playClip(name: String) {
        if (_state.value.phase != Phase.IDLE || engine == null) return
        val file = ClipProvider.clips(getApplication()).firstOrNull { it.name == name } ?: return
        runTurn(InputSource.CLIP, null) {
            withContext(Dispatchers.IO) {
                try {
                    WavReader.read(file.readBytes())
                } catch (e: IOException) {
                    FloatArray(0)
                }
            }
        }
    }

    private fun runTurn(
        source: InputSource,
        utteranceEndMs: Long?,
        audio: suspend () -> FloatArray,
    ) {
        val turnEngine = engine ?: return
        _state.update { it.copy(phase = Phase.THINKING, message = null) }
        turnJob =
            viewModelScope.launch {
                try {
                    val pcm = audio()
                    val result = turnEngine.handle(pcm, source, utteranceEndMs ?: clock.millis())
                    _state.update {
                        it.copy(
                            phase = Phase.SPEAKING,
                            screen = result.screen,
                            lastSpoken = result.spoken,
                            awaitingConfirmation = result.awaitingConfirmation,
                            lastTrace = result.trace,
                        )
                    }
                    // Capture stays off until speech is done (SG-8).
                    speaker.speak(result.spoken)
                } finally {
                    _state.update { it.copy(phase = Phase.IDLE, awaitingConfirmation = engine?.isAwaitingConfirmation ?: false) }
                }
            }
    }

    fun selectScenario(name: String) {
        DrivingScenario.ALL.firstOrNull { it.name == name }?.let(vehicle::play)
    }

    fun setConnected(connected: Boolean) = vehicle.setConnected(connected)

    fun refreshClips() {
        _state.update { it.copy(clips = ClipProvider.clips(getApplication()).map { f -> f.name }) }
    }

    override fun onCleared() {
        speaker.shutdown()
        // The scope is already cancelled; wait for aborted native calls (a turn, the language-model
        // warm-up) to return before freeing the engines.
        runBlocking {
            turnJob?.join()
            warmup?.join()
        }
        speech?.close()
        llm?.close()
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.HARDWARE == "ranchu" ||
            Build.HARDWARE == "goldfish" ||
            Build.PRODUCT.contains("sdk")

    private companion object {
        const val TICK_MS = 200L
        const val TAG = "earshot"
    }
}
