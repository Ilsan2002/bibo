package com.bibo.ui

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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class SpeechState { IDLE, LISTENING, FINISHING }
private fun localRecognitionAvailable(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
    runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

/** Tap start/stop or hold and release. Cancellation never submits partial/error speech. */
@Composable
fun HoldToTalkButton(onTranscript: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    onRecordingStart: () -> Unit = {}, compact: Boolean = false) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptics = rememberHaptics()
    val gestureScope = rememberCoroutineScope()
    val prefs = remember { context.applicationContext.getSharedPreferences("bibo_speech_privacy", Context.MODE_PRIVATE) }
    var networkOptIn by remember { mutableStateOf(prefs.getBoolean("allow_network_speech", false)) }
    var showPrivacy by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(SpeechState.IDLE) }
    var message by remember { mutableStateOf<String?>(null) }
    var partial by remember { mutableStateOf("") }
    var generation by remember { mutableLongStateOf(0) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val currentTranscript by rememberUpdatedState(onTranscript)
    val currentRecordingStart by rememberUpdatedState(onRecordingStart)
    val currentEnabled by rememberUpdatedState(enabled)
    var localAvailable by remember { mutableStateOf(localRecognitionAvailable(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        localAvailable = localRecognitionAvailable(context)
        networkOptIn = prefs.getBoolean("allow_network_speech", false)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        message = if (granted) "Microphone allowed. Tap to start." else "Microphone denied. Allow it in app settings or type your log."
    }
    fun disposeRecognizer() { recognizer?.cancel(); recognizer?.destroy(); recognizer = null }
    fun cancel() { generation++; state = SpeechState.IDLE; partial = ""; disposeRecognizer() }
    fun stop() { if (state == SpeechState.LISTENING) { state = SpeechState.FINISHING; recognizer?.stopListening() } }
    fun start() {
        if (!currentEnabled || state != SpeechState.IDLE) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO); return
        }
        localAvailable = localRecognitionAvailable(context)
        networkOptIn = prefs.getBoolean("allow_network_speech", false)
        if (!localAvailable && !networkOptIn) { showPrivacy = true; return }
        if (!localAvailable && !SpeechRecognizer.isRecognitionAvailable(context)) { message = "No speech service installed. Type your log instead."; return }
        try {
            disposeRecognizer()
            val session = ++generation
            val request = if (localAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = request
            request.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    if (session != generation) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    generation++; state = SpeechState.IDLE; partial = ""; disposeRecognizer()
                    if (text.isNullOrBlank()) { message = "No speech captured. Retry or type your log."; haptics.reject() }
                    else { message = null; haptics.confirm(); currentTranscript(text) }
                }
                override fun onError(error: Int) {
                    if (session != generation) return
                    generation++; state = SpeechState.IDLE; partial = ""; disposeRecognizer(); haptics.reject()
                    message = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission changed. Allow it or type your log."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service could not connect. Retry or type your log."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy. Wait a moment and retry."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "English speech is unavailable. Install the speech language or type your log."
                        else -> "Speech was not completed. Retry or type your log."
                    }
                }
                override fun onPartialResults(results: Bundle?) { if (session == generation) partial = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty() }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            currentRecordingStart()
            state = SpeechState.LISTENING; message = null; partial = ""
            request.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                if (localAvailable) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } catch (e: Exception) { cancel(); message = "Speech could not start. Retry or type your log." }
    }
    fun toggle() { if (state == SpeechState.LISTENING) stop() else if (state == SpeechState.IDLE) start() }
    val startAction by rememberUpdatedState({ start() })
    val stopAction by rememberUpdatedState({ stop() })
    val cancelAction by rememberUpdatedState({ cancel() })
    val toggleAction by rememberUpdatedState({ toggle() })
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) cancelAction() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancelAction() }
    }
    LaunchedEffect(enabled) { if (!enabled) cancel() }
    LaunchedEffect(state) {
        when (state) {
            SpeechState.LISTENING -> { delay(45_000); stop() }
            SpeechState.FINISHING -> { delay(10_000); cancel(); message = "Speech timed out. Retry or type your log." }
            SpeechState.IDLE -> Unit
        }
    }
    Column(modifier.widthIn(max = 240.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (partial.isNotBlank()) Text(partial, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        Surface(shape = CircleShape, color = if (state == SpeechState.LISTENING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(64.dp).semantics {
                role = Role.Button
                if (!enabled || state == SpeechState.FINISHING) disabled()
                customActions = listOf(CustomAccessibilityAction("Speech privacy") { cancel(); showPrivacy = true; true })
                stateDescription = when (state) { SpeechState.IDLE -> "Ready to record"; SpeechState.LISTENING -> "Recording"; SpeechState.FINISHING -> "Processing speech" }
                onClick(label = if (state == SpeechState.LISTENING) "Stop recording" else "Start recording") { if (enabled && state != SpeechState.FINISHING) { toggleAction(); true } else false }
            }.pointerInput(Unit) {
                detectTapGestures(onPress = {
                    var held = false
                    val hold = gestureScope.launch { delay(350); held = true; startAction() }
                    val released = tryAwaitRelease()
                    hold.cancel()
                    if (held) { if (released) stopAction() else cancelAction() }
                    else if (released) toggleAction()
                })
            }) {
            Box(contentAlignment = Alignment.Center) { Icon(if (state == SpeechState.LISTENING) Icons.Filled.Stop else Icons.Filled.Mic, if (state == SpeechState.LISTENING) "Stop voice logging" else "Tap or hold to log by voice") }
        }
        if (!compact) Text(if (state == SpeechState.FINISHING) "Processing…" else if (state == SpeechState.LISTENING) "Tap to stop · slide away to cancel" else "Tap or hold to speak", style = MaterialTheme.typography.labelSmall)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (!compact) TextButton(onClick = { cancel(); showPrivacy = true }, contentPadding = PaddingValues(0.dp)) { Text(if (localAvailable) "On-device speech" else if (networkOptIn) "Network speech enabled" else "Speech privacy", style = MaterialTheme.typography.labelSmall) }
    }
    if (showPrivacy) AlertDialog(onDismissRequest = { showPrivacy = false }, title = { Text("Speech privacy") }, text = {
        Text(if (localAvailable) "Speech audio is transcribed on this device. The transcript follows the screen's save or send action."
            else "This device has no on-device speech recognizer. If you enable network speech, the phone's speech provider may send audio off your device. You can always type a log instead.")
    }, confirmButton = { TextButton(onClick = {
        if (!localAvailable) { networkOptIn = !networkOptIn; prefs.edit().putBoolean("allow_network_speech", networkOptIn).apply() }
        showPrivacy = false
    }) { Text(if (localAvailable) "Done" else if (networkOptIn) "Disable network speech" else "Enable network speech") } }, dismissButton = { if (!localAvailable) TextButton(onClick = { showPrivacy = false }) { Text("Keep current choice") } })
}
