package com.bibo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Thin wrapper over Compose semantic haptics. Uses the richer HapticFeedbackType
 * constants added in Compose UI 1.8 — no VIBRATE permission, automatic device fallback.
 */
class Haptics(private val hf: HapticFeedback) {
    fun confirm() = hf.performHapticFeedback(HapticFeedbackType.Confirm)
    fun reject() = hf.performHapticFeedback(HapticFeedbackType.Reject)
    fun toggleOn() = hf.performHapticFeedback(HapticFeedbackType.ToggleOn)
    fun toggleOff() = hf.performHapticFeedback(HapticFeedbackType.ToggleOff)
    fun tick() = hf.performHapticFeedback(HapticFeedbackType.SegmentTick)
    fun longPress() = hf.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
fun rememberHaptics(): Haptics {
    val hf = LocalHapticFeedback.current
    return remember(hf) { Haptics(hf) }
}
