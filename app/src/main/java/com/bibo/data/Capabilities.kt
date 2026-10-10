package com.bibo.data

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Capability { USAGE_ACCESS, CALENDAR, NOTIFICATIONS, MICROPHONE, ACCESSIBILITY, OVERLAY, DND, LOCAL_SPEECH }
data class CapabilityState(val capability: Capability, val granted: Boolean, val healthy: Boolean, val detail: String)
data class CapabilitySnapshot(val states: List<CapabilityState>) {
    fun state(capability: Capability): CapabilityState = states.first { it.capability == capability }
}

object WebsiteTrackingSettings {
    private const val PREFS = "bibo_privacy"
    private val failure = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = failure.asStateFlow()
    fun reportError(message: String?) { failure.value = message }
    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("website_recording", false)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("website_recording", enabled).apply()
    }
}

/** A granted switch is distinct from a connected, functioning service. Never persisted as healthy. */
object Capabilities {
    private val connected = MutableStateFlow(false)
    val accessibilityConnection: StateFlow<Boolean> = connected.asStateFlow()
    fun setAccessibilityServiceConnected(context: Context, value: Boolean) { connected.value = value }
    fun accessibilityServiceConnected(context: Context): Boolean = connected.value && isWebTrackingEnabled(context)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun snapshot(context: Context): CapabilitySnapshot {
        val usage = UsageRepo(context).hasPermission()
        val calendarRead = granted(context, Manifest.permission.READ_CALENDAR)
        val calendarWrite = granted(context, Manifest.permission.WRITE_CALENDAR)
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val microphone = granted(context, Manifest.permission.RECORD_AUDIO)
        val accessibility = isWebTrackingEnabled(context)
        val overlay = Settings.canDrawOverlays(context)
        val dnd = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
        val localSpeech = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return CapabilitySnapshot(listOf(
            CapabilityState(Capability.USAGE_ACCESS, usage, usage, if (usage) "Collection available; saved history is always readable" else "Collection paused; saved history remains readable"),
            CapabilityState(Capability.CALENDAR, calendarRead && calendarWrite, calendarRead && calendarWrite,
                when { !calendarRead -> "Calendar access denied"; !calendarWrite -> "Read access only; writing unavailable"; else -> "Read and write access granted" }),
            CapabilityState(Capability.NOTIFICATIONS, notifications, notifications, if (notifications) "Reminders and session notifications allowed" else "Notifications blocked"),
            CapabilityState(Capability.MICROPHONE, microphone, microphone, if (microphone) "Microphone allowed" else "Voice input needs microphone access"),
            CapabilityState(Capability.ACCESSIBILITY, accessibility, accessibilityServiceConnected(context),
                when { !accessibility -> "Accessibility access disabled"; !accessibilityServiceConnected(context) -> "Granted, but service not connected"; else -> "Service connected; blocking available" }),
            CapabilityState(Capability.OVERLAY, overlay, overlay, if (overlay) "Blocking overlay available" else "Optional overlay disabled; blocker can return to Home"),
            CapabilityState(Capability.DND, dnd, dnd, if (dnd) "Do Not Disturb access granted" else "Automatic silencing unavailable"),
            CapabilityState(Capability.LOCAL_SPEECH, localSpeech, localSpeech, if (localSpeech) "Local speech engine available" else "Local speech engine unavailable; use typing or an explicitly chosen provider"),
        ))
    }

    fun openSettings(context: Context, capability: Capability): Result<Unit> = runCatching {
        val intent = when (capability) {
            Capability.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            Capability.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            Capability.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            Capability.DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            Capability.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            Capability.LOCAL_SPEECH -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
