package ai.schism.split.expense.edit.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** What the voice recogniser is doing right now, so the UI can show clear feedback. */
enum class VoicePhase { Idle, Listening, Working }

/** Controls voice capture and exposes its live [phase] for UI feedback. */
class VoiceController(
    val phase: State<VoicePhase>,
    val start: () -> Unit,
    val cancel: () -> Unit,
)

/**
 * On-device speech-to-text for the expense form. Returns a [VoiceController] whose [start] begins
 * listening and delivers the final transcript to [onResult]; its [phase] drives visible feedback
 * (Listening / Working) so the user always knows whether the mic is live. Everything stays on the
 * device — API 33+ prefers the offline on-device recogniser. RECORD_AUDIO is requested at first use.
 */
@Composable
fun rememberVoiceInput(onResult: (String) -> Unit): VoiceController {
    val context = LocalContext.current
    val latestOnResult = rememberUpdatedState(onResult)
    val phase = remember { mutableStateOf(VoicePhase.Idle) }

    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) createRecognizer(context) else null
    }

    DisposableEffect(recognizer) {
        onDispose { recognizer?.destroy() }
    }

    val startListening = remember(recognizer) {
        {
            if (recognizer == null) {
                phase.value = VoicePhase.Idle
            } else {
                phase.value = VoicePhase.Listening
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { phase.value = VoicePhase.Listening }
                    override fun onBeginningOfSpeech() { phase.value = VoicePhase.Listening }
                    override fun onEndOfSpeech() { phase.value = VoicePhase.Working }
                    override fun onResults(results: Bundle?) {
                        phase.value = VoicePhase.Idle
                        results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { latestOnResult.value(it) }
                    }
                    override fun onError(error: Int) { phase.value = VoicePhase.Idle }
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                recognizer.startListening(recognitionIntent())
            }
            Unit
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) startListening()
    }

    return remember(recognizer) {
        VoiceController(
            phase = phase,
            start = {
                if (hasRecordAudioPermission(context)) startListening()
                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            cancel = {
                recognizer?.cancel()
                phase.value = VoicePhase.Idle
            },
        )
    }
}

/** Modal shown while the mic is live so the user knows it's listening / processing. */
@Composable
fun VoiceListeningDialog(controller: VoiceController) {
    val phase by controller.phase
    if (phase == VoicePhase.Idle) return

    AlertDialog(
        onDismissRequest = controller.cancel,
        confirmButton = {
            TextButton(onClick = controller.cancel) {
                Text(if (phase == VoicePhase.Listening) "Stop" else "Cancel")
            }
        },
        icon = {
            val pulse = rememberInfiniteTransition(label = "mic")
            val scale by pulse.animateFloat(
                initialValue = 1f, targetValue = 1.25f,
                animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "s",
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(64.dp).scale(if (phase == VoicePhase.Listening) scale else 1f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        },
        title = { Text(if (phase == VoicePhase.Listening) "Listening…" else "Working…") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (phase == VoicePhase.Listening) {
                        "Say something like \"paid 800 for dinner, split with Riya and Sam\"."
                    } else {
                        "Turning your words into an expense…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
    )
}

private fun createRecognizer(context: Context): SpeechRecognizer =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    ) {
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    } else {
        SpeechRecognizer.createSpeechRecognizer(context)
    }

private fun recognitionIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

private fun hasRecordAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
