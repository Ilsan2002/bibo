package com.bibo.data

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * URL-bar view IDs per browser. The accessibility service reads the currently focused
 * browser's address bar to know which site is on screen. IDs verified from the curbox
 * project and the Samsung/Edge/DuckDuckGo accessibility samples in the research pass.
 */
val BROWSER_URL_BAR_IDS: Map<String, String> = mapOf(
    "com.android.chrome" to "com.android.chrome:id/url_bar",
    "com.chrome.beta" to "com.chrome.beta:id/url_bar",
    "com.brave.browser" to "com.brave.browser:id/url_bar",
    "com.vivaldi.browser" to "com.vivaldi.browser:id/url_bar",
    "org.cromite.cromite" to "org.cromite.cromite:id/url_bar",
    "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
    "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
    "com.opera.browser" to "com.opera.browser:id/url_field",
    "com.opera.mini.native" to "com.opera.mini.native:id/url_field",
    "com.duckduckgo.mobile.android" to "com.duckduckgo.mobile.android:id/omnibarTextInput",
    "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
    "org.mozilla.focus" to "org.mozilla.focus:id/mozac_browser_toolbar_url_view",
)

/** Extracts a registrable-ish domain from URL-bar text, or null if it isn't a URL. */
fun extractDomain(raw: String?): String? {
    val t = raw?.trim()?.lowercase() ?: return null
    if (t.isEmpty() || t.contains(' ')) return null // search query, not a URL
    var host = t
        .substringAfter("://", t)
        .substringBefore('/')
        .substringBefore('?')
        .removePrefix("www.")
    host = host.substringBefore(':') // strip port
    if (!host.contains('.') || host.any { it.isWhitespace() }) return null
    if (host.startsWith("localhost") || host.all { it.isDigit() || it == '.' }) return null
    return host.takeIf { it.length in 3..80 }
}

/** True if the user has enabled Bibo's accessibility service. */
fun isWebTrackingEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val name = "${context.packageName}/${WebTrackingService::class.java.name}"
    return enabled.split(':').any { it.equals(name, ignoreCase = true) }
}

fun openAccessibilitySettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/**
 * Reads the active browser's URL bar to log per-domain time. Commits a session when the
 * domain changes, the browser is left, or the screen turns off. A 15s heartbeat re-reads
 * the bar so browsers that don't emit content events (Firefox/GeckoView) are still tracked.
 */
class WebTrackingService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentDomain: String? = null
    private var sessionStart = 0L

    private val heartbeat = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, 15_000L)
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) commitCurrent()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Events don't fire reliably on all OEMs (One UI), so the periodic heartbeat is
        // the primary sampler; events, when they arrive, refine it.
        handler.postDelayed(heartbeat, 8_000L)
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        sample()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(heartbeat)
        runCatching { unregisterReceiver(screenOffReceiver) }
        commitCurrent()
    }

    private fun sample() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        val viewId = BROWSER_URL_BAR_IDS[pkg]
        if (viewId == null) {
            commitCurrent() // left the browser for another app
            return
        }
        val text = runCatching {
            root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull()?.text?.toString()
        }.getOrNull()
        val domain = extractDomain(text) ?: return // toolbar hidden / search: keep session
        if (domain != currentDomain) {
            commitCurrent()
            currentDomain = domain
            sessionStart = System.currentTimeMillis()
        }
    }

    private fun commitCurrent() {
        val domain = currentDomain ?: return
        val start = sessionStart
        currentDomain = null
        sessionStart = 0L
        if (start <= 0L) return
        val end = System.currentTimeMillis()
        if (end - start < 3_000L) return
        scope.launch {
            runCatching {
                BiboDb.get(applicationContext).websites()
                    .insert(WebsiteSession(domain = domain, startMillis = start, endMillis = end))
            }
        }
    }
}
