package io.github.ardaulas.earshot.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ardaulas.earshot.core.policy.DrivingState
import io.github.ardaulas.earshot.core.trace.TurnTrace
import io.github.ardaulas.earshot.core.turn.ScreenContent
import io.github.ardaulas.earshot.core.vehicle.ClimateProperty
import io.github.ardaulas.earshot.core.vehicle.DrivingScenario
import java.util.Locale

@Composable
fun AssistantScreen(
    viewModel: AssistantViewModel,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Pinned above the scrolling content so it stays visible while the clip controls are used.
            // Debug caption and developer details are hidden unless parked (re-audit N6).
            state.clipCaption?.takeIf { state.drivingState == DrivingState.PARKED }?.let {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) { RecordingCaption(it, state) }
            }
            AssistantContent(state, onPress, onRelease, viewModel)
        }
    }
}

@Composable
private fun AssistantContent(
    state: UiState,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    viewModel: AssistantViewModel,
) {
    run {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(state)
            AssistantPanel(state, onPress, onRelease)
            DeveloperPanel(
                state = state,
                onScenario = viewModel::selectScenario,
                onConnected = viewModel::setConnected,
                onClip = viewModel::playClip,
            )
        }
    }
}

/**
 * Debug builds only. Captions a clip-driven turn for screen recordings, which carry no audio: what the
 * synthetic clip says and what the assistant replied. A recording aid, not part of the in-car interface.
 */
@Composable
private fun RecordingCaption(
    clip: String,
    state: UiState,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.inverseSurface, MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val style = MaterialTheme.typography.bodyMedium
        val color = MaterialTheme.colorScheme.inverseOnSurface
        Text(
            "Recording caption (debug build, synthetic clip input) \u00B7 ${state.drivingState}",
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
        Text("Clip says: \u201C$clip\u201D", style = style, color = color, fontWeight = FontWeight.SemiBold)
        val reply = if (state.phase == Phase.THINKING) "\u2026" else state.lastSpoken?.let { "\u201C$it\u201D" } ?: "\u2026"
        Text("Assistant says: $reply", style = style, color = color)
    }
}

@Composable
private fun Header(state: UiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Earshot", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        val (label, color) =
            when (state.drivingState) {
                DrivingState.PARKED -> "PARKED" to MaterialTheme.colorScheme.secondary
                DrivingState.MOVING -> "MOVING" to MaterialTheme.colorScheme.primary
                DrivingState.UNKNOWN -> "UNKNOWN → MOVING" to MaterialTheme.colorScheme.error
            }
        Text(
            label,
            color = MaterialTheme.colorScheme.surface,
            style = MaterialTheme.typography.labelLarge,
            modifier =
                Modifier
                    .background(color, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun AssistantPanel(
    state: UiState,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val moving = state.drivingState.effective == DrivingState.MOVING
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val models = state.models) {
                ModelStatus.Checking -> {
                    Text("Verifying models…")
                }

                is ModelStatus.Disabled -> {
                    Text("Assistant disabled", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    Text(models.reason, textAlign = TextAlign.Center)
                }

                is ModelStatus.Ready -> {
                    Unit
                }
            }
            val enabled = state.models is ModelStatus.Ready
            val label =
                when (state.phase) {
                    Phase.IDLE -> "Hold to talk"
                    Phase.LISTENING -> "Listening…"
                    Phase.THINKING -> "Working…\ntap to cancel"
                    Phase.SPEAKING -> "Speaking…"
                }
            val buttonColor =
                when {
                    !enabled -> MaterialTheme.colorScheme.surfaceVariant
                    state.phase == Phase.LISTENING -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.primary
                }
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(168.dp)
                        .background(buttonColor, CircleShape)
                        .semantics { contentDescription = "Push to talk" }
                        .then(
                            if (enabled) {
                                Modifier.pointerInput(Unit) {
                                    detectTapGestures(onPress = {
                                        onPress()
                                        tryAwaitRelease()
                                        onRelease()
                                    })
                                }
                            } else {
                                Modifier
                            },
                        ),
            ) {
                Text(label, color = MaterialTheme.colorScheme.surface, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            }
            if (state.awaitingConfirmation) Text("Waiting for yes or no", fontWeight = FontWeight.SemiBold)
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }

            // Screen output exists only when the policy allowed it (parked), and is rendered only while
            // still parked (re-audit #7).
            when (val screen = state.screen?.takeIf { state.drivingState == DrivingState.PARKED }) {
                is ScreenContent.Text -> {
                    Text(screen.text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }

                is ScreenContent.ClimatePanel -> {
                    ClimateValues(screen.values)
                }

                null -> {
                    if (moving) {
                        Text("Voice only while driving", style = MaterialTheme.typography.bodySmall)
                    } else if (state.ttsAvailable == false && state.lastSpoken != null) {
                        // No speech engine: short text instead, only when parked (degradation table).
                        Text(state.lastSpoken, textAlign = TextAlign.Center)
                    }
                }
            }
            if (state.ttsAvailable == false) {
                Text("Text-to-speech is unavailable.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ClimateValues(values: Map<ClimateProperty, Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        values.toSortedMap().forEach { (property, value) -> Text("${label(property)}: ${format(property, value)}") }
    }
}

@Composable
private fun DeveloperPanel(
    state: UiState,
    onScenario: (String) -> Unit,
    onConnected: (Boolean) -> Unit,
    onClip: (String) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Developer view", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            // Under the platform's UX restrictions the activity is distraction optimized: no test
            // controls, model details or free text. Only a debug build keeps its clip player, the test
            // instrument for the moving rows of the manual test plan (re-audit 3, N6).
            if (state.uxRestricted == true && state.drivingState != DrivingState.PARKED) {
                Text("Hidden while driving (platform UX restrictions).", style = MaterialTheme.typography.bodySmall)
                // Debug builds only; clip numbers, never file names, while restricted (re-audit 4, N6).
                if (state.clips.isNotEmpty()) {
                    ClipPicker(state.clips, enabled = state.phase == Phase.IDLE, onClip = onClip, showNames = false)
                }
                return@Column
            }
            if (state.carApi) {
                Text(
                    if (state.realClimateWrites) {
                        "CAR API: real driving-state signals and climate writes (emulator vehicle HAL). Test tool, not in-car UI."
                    } else {
                        "CAR API: real driving-state signals; simulated climate writes. Test tool, not in-car UI."
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                val ux =
                    when (state.uxRestricted) {
                        true -> "required (voice only)"
                        false -> "not required"
                        null -> "unavailable"
                    }
                Text("Platform UX restrictions: $ux", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "SIMULATED VEHICLE. This panel is a test tool, not part of the in-car interface.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DrivingScenario.ALL.forEach { s ->
                        FilterChip(selected = state.scenario == s.name, onClick = { onScenario(s.name) }, label = { Text(s.name) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vehicle connection", Modifier.weight(1f))
                    Switch(checked = state.connected, onCheckedChange = onConnected)
                }
            }
            val speed = state.speedKmh?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "no signal"
            Text("Signals: $speed, gear ${state.gear?.name?.lowercase() ?: "no signal"} → ${state.drivingState}")
            val parked = state.drivingState == DrivingState.PARKED
            // Climate values and the last turn (transcript, reply, timings) only while parked (re-audit N6).
            if (parked) ClimateValues(state.climate) else Text("Details hidden while driving", style = MaterialTheme.typography.bodySmall)
            (state.models as? ModelStatus.Ready)?.takeIf { parked }?.let {
                Text("Speech model: ${it.speechModel}", style = MaterialTheme.typography.bodySmall)
                it.lmNote?.let { note -> Text("Language model: $note", style = MaterialTheme.typography.bodySmall) }
            }
            if (state.clips.isNotEmpty()) ClipPicker(state.clips, enabled = state.phase == Phase.IDLE, onClip = onClip)
            if (parked) state.lastTrace?.let { LastTurn(it) }
        }
    }
}

@Composable
private fun ClipPicker(
    clips: List<String>,
    enabled: Boolean,
    onClip: (String) -> Unit,
    showNames: Boolean = true,
) {
    var index by remember { mutableIntStateOf(0) }
    val clip = clips[index.coerceIn(clips.indices)]
    Text("Test clip (debug builds only)", style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { index = (index - 1 + clips.size) % clips.size }) { Text("◀") }
        val label = if (showNames) clip else "clip ${index.coerceIn(clips.indices) + 1} of ${clips.size}"
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        TextButton(onClick = { index = (index + 1) % clips.size }) { Text("▶") }
    }
    OutlinedButton(onClick = { onClip(clip) }, enabled = enabled) { Text("Play clip") }
}

@Composable
private fun LastTurn(trace: TurnTrace) {
    HorizontalDivider()
    Text("Last turn", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    AssistChip(onClick = {}, label = { Text("input: ${trace.inputSource.name.lowercase()}") })
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val confidence = trace.asrConfidence?.let { String.format(Locale.US, "%.2f", it) } ?: "unknown"
    Text("heard: \"${trace.transcript.orEmpty()}\" (confidence $confidence)", style = mono)
    Text("command: ${trace.command ?: "-"} [${trace.commandSource ?: "-"}]", style = mono)
    trace.lmOutcome?.let { Text("lm: $it", style = mono) }
    Text("verdict: ${trace.verdict ?: "-"} → ${trace.outcome}", style = mono)
    Text("said: \"${trace.spoken}\"", style = mono)
    trace.stages.forEach { s ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.stage.padEnd(7), style = mono, modifier = Modifier.width(64.dp))
            Box(
                Modifier
                    .height(8.dp)
                    .width((s.durationMs / 20.0).coerceIn(1.0, 200.0).dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Text(String.format(Locale.US, "  %.0f ms", s.durationMs), style = mono)
        }
    }
    Text(String.format(Locale.US, "total %.0f ms on %s", trace.totalMs, trace.host.device), style = mono)
    Text(trace.host.note, style = MaterialTheme.typography.labelSmall)
}

private fun label(p: ClimateProperty) =
    when (p) {
        ClimateProperty.CABIN_TEMPERATURE_C -> "Temperature"
        ClimateProperty.FAN_LEVEL -> "Fan"
        ClimateProperty.FRONT_DEFROST -> "Front defrost"
        ClimateProperty.REAR_DEFROST -> "Rear defrost"
        ClimateProperty.AC -> "AC"
    }

private fun format(
    p: ClimateProperty,
    v: Int,
) = when (p) {
    ClimateProperty.CABIN_TEMPERATURE_C -> "$v °C"
    ClimateProperty.FAN_LEVEL -> if (v == 0) "off" else "level $v"
    else -> if (v == 1) "on" else "off"
}
