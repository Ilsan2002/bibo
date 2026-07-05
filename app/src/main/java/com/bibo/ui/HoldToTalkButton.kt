package com.bibo.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import java.util.Locale
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Circular press-and-hold microphone button. While held it streams speech to the
 * on-device recognizer; on release the final transcript is delivered to [onTranscript].
 */
@Composable
fun HoldToTalkButton(
    onTranscript: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var isListening by remember { mutableStateOf(false) }
    var partialText by remember { mutableStateOf("") }
    val currentOnTranscript by rememberUpdatedState(onTranscript)

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAudioPermission = granted }

    val recognizer = remember { buildRecognizer(context) }
    DisposableEffect(recognizer) {
        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?: partialText.ifBlank { null }
                partialText = ""
                if (!text.isNullOrBlank()) {
                    haptics.confirm()
                    currentOnTranscript(text)
                } else {
                    haptics.reject()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.let { partialText = it }
            }

            override fun onError(error: Int) {
                isListening = false
                // fall back to whatever partial text we heard
                val text = partialText
                partialText = ""
                if (text.isNotBlank()) currentOnTranscript(text)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        recognizer?.setRecognitionListener(listener)
        onDispose { recognizer?.destroy() }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = if (isListening) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(64.dp)
                .pointerInput(hasAudioPermission, recognizer) {
                    detectTapGestures(
                        onPress = {
                            if (recognizer == null) return@detectTapGestures
                            if (!hasAudioPermission) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return@detectTapGestures
                            }
                            partialText = ""
                            isListening = true
                            recognizer.startListening(recognizeIntent(context))
                            tryAwaitRelease()
                            recognizer.stopListening()
                        }
                    )
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = "Hold to log by voice",
                    tint = if (isListening) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                )
            }
        }
        if (isListening && partialText.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Text(
                    partialText,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * Builds a recognizer, preferring Google's speech service over the system default.
 * On Samsung the default is often One UI's own recognizer, which transcribes noticeably
 * worse than Google's — binding Google's service directly is the single biggest quality win.
 */
private fun buildRecognizer(context: Context): SpeechRecognizer? {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
    val google = ComponentName(
        "com.google.android.googlequicksearchbox",
        "com.google.android.voicesearch.serviceapi.GoogleRecognitionService",
    )
    val hasGoogle = runCatching { context.packageManager.getServiceInfo(google, 0) }.isSuccess
    return if (hasGoogle) {
        SpeechRecognizer.createSpeechRecognizer(context, google)
    } else {
        SpeechRecognizer.createSpeechRecognizer(context)
    }
}

private fun recognizeIntent(context: Context): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // English keeps recognition aligned with the (English) phrase parser.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.US.toLanguageTag())
        // Deliberately NOT setting EXTRA_PREFER_OFFLINE: the online model is much more
        // accurate, and the phrase is short so latency is fine.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            putExtra(
                RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY,
            )
        }
    }
