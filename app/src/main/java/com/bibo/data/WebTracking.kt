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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import androidx.room.withTransaction

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
    val t = raw?.trim()?.lowercase(java.util.Locale.ROOT) ?: return null
    if (t.isEmpty() || t.any { it.isWhitespace() }) return null
    if (listOf("mailto:", "about:", "javascript:", "data:", "file:", "content:").any(t::startsWith)) return null
    val uri = runCatching { java.net.URI(if (t.contains("://")) t else "https://$t") }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https")) return null
    val authority = uri.rawAuthority ?: return null
    val rawHost = authority.substringAfterLast('@').substringBefore(':').removePrefix("www.").trimEnd('.')
    val host = runCatching { java.net.IDN.toASCII(rawHost, java.net.IDN.USE_STD3_ASCII_RULES) }.getOrNull() ?: return null
    if (!host.contains('.') || host.all { it.isDigit() || it == '.' } || host.length > 253) return null
    return host.takeIf { it.split('.').all { label -> label.isNotEmpty() && label.length <= 63 && !label.startsWith('-') && !label.endsWith('-') } }
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

/** Whether Bibo can draw the block screen over other apps. */
fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

fun requestOverlayPermission(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

data class AppInfo(val packageName: String, val label: String)

/** Launchable apps the user can choose to block (excludes Bibo itself), sorted by name. */
fun launchableApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val protected = protectedFocusPackages(context)
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg in protected) return@mapNotNull null
            AppInfo(pkg, ri.loadLabel(pm).toString())
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

private fun protectedFocusPackages(context: Context): Set<String> {
    val homes = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        .map { it.activityInfo.packageName }
    return setOf(context.packageName, "com.android.systemui", "com.android.settings", "com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher") + homes
}

/**
 * Reads the active browser's URL bar to log per-domain time. Commits a session when the
 * domain changes, the browser is left, or the screen turns off. A 15s heartbeat re-reads
 * the bar so browsers that don't emit content events (Firefox/GeckoView) are still tracked.
 */
class WebTrackingService : AccessibilityService() {
    companion object {
        private fun historyGeneration(context: Context): Long = context.getSharedPreferences("bibo_web_history_generation", Context.MODE_PRIVATE).getLong("generation", 0)
        fun resetRecordingHistory(context: Context) {
            val p = context.getSharedPreferences("bibo_web_history_generation", Context.MODE_PRIVATE)
            p.edit().putLong("generation", p.getLong("generation", 0) + 1).apply()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sessionObserver: Job? = null
    private var receiverRegistered = false

    private var currentDomain: String? = null
    private var sessionStart = 0L
    private var sessionElapsedStart = 0L
    private var currentBrowser: String? = null
    private var lastUrlSample = 0L
    private var recordingGeneration = -1L

    private var overlay: android.view.View? = null
    private var overlayForPkg: String? = null

    private val systemPackages by lazy { protectedFocusPackages(this) }
    private var screenOn = true

    // Runs faster while a focus session is actively blocking, so a blocked app is covered
    // within ~1s; otherwise it just ticks the URL sampler every 15s.
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (screenOn && WebsiteTrackingSettings.enabled(this@WebTrackingService) && now - lastUrlSample >= 15_000L) {
                sample()
                checkpointCurrent()
                lastUrlSample = now
            }
            val blocking = blockingActive()
            if (blocking && screenOn) enforceBlock() else removeOverlay()
            if (!WebsiteTrackingSettings.enabled(this@WebTrackingService)) commitCurrent()
            val focusConfigured = TimerController.isFocus(this@WebTrackingService) && TimerController.blockedApps(this@WebTrackingService).isNotEmpty()
            handler.postDelayed(this, if (focusConfigured) 1_200L else 15_000L)
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; commitCurrent(); removeOverlay() }
                Intent.ACTION_SCREEN_ON -> { screenOn = true; lastUrlSample = 0 }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Capabilities.setAccessibilityServiceConnected(this, true)
        screenOn = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
        AppScope.io.launch { TimerController.initialize(applicationContext) }
        sessionObserver?.cancel()
        sessionObserver = scope.launch(Dispatchers.Main) {
            TimerController.state.collect { handler.removeCallbacks(tick); handler.post(tick) }
        }
        // Events don't fire reliably on all OEMs (One UI), so the periodic tick is the
        // primary sampler / blocker; events, when they arrive, refine it.
        if (!receiverRegistered) {
            registerReceiver(screenOffReceiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) })
            receiverRegistered = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (screenOn && WebsiteTrackingSettings.enabled(this)) sample()
        if (blockingActive() && screenOn) enforceBlock() else removeOverlay()
    }

    override fun onInterrupt() { commitCurrent(); removeOverlay() }

    override fun onDestroy() {
        super.onDestroy()
        Capabilities.setAccessibilityServiceConnected(this, false)
        handler.removeCallbacks(tick)
        sessionObserver?.cancel()
        removeOverlay()
        if (receiverRegistered) runCatching { unregisterReceiver(screenOffReceiver) }
        receiverRegistered = false
        commitCurrent()
    }

    // ---- focus app blocking --------------------------------------------------

    private fun blockingActive(): Boolean {
        if (!TimerController.isRunning(this) || !TimerController.isFocus(this)) return false
        if (TimerController.blockedApps(this).isEmpty()) return false
        // don't block during a Pomodoro break
        if (TimerController.isPomodoro(this) && TimerController.phase(this) == TimerController.PHASE_BREAK) {
            return false
        }
        return true
    }

    private fun enforceBlock() {
        // An app overlay must not become its own blocker target. Read the underlying
        // application window rather than the active Bibo overlay when one is present.
        val appRoot = if (overlay != null) windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() != packageName }?.root else rootInActiveWindow
        val pkg = appRoot?.packageName?.toString() ?: run { removeOverlay(); return }
        if (pkg in systemPackages) {
            removeOverlay()
            return
        }
        if (pkg in TimerController.blockedApps(this)) showOverlay(pkg) else removeOverlay()
    }

    private fun showOverlay(pkg: String) {
        if (!Settings.canDrawOverlays(this)) {
            removeOverlay()
            performGlobalAction(GLOBAL_ACTION_HOME) // fallback: bounce to home
            return
        }
        if (overlay != null && overlayForPkg == pkg) return
        removeOverlay()
        val view = buildOverlay(pkg)
        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
        runCatching {
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).addView(view, params)
            overlay = view
            overlayForPkg = pkg
        }.onFailure { performGlobalAction(GLOBAL_ACTION_HOME) }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        overlayForPkg = null
        runCatching {
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(v)
        }
    }

    private fun buildOverlay(pkg: String): android.view.View {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("This app")
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xF20C0E13.toInt())
            setPadding(px(40), px(40), px(40), px(40))

            addView(android.widget.TextView(context).apply {
                text = "🎯 Stay in your session"
                textSize = 24f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
            })
            addView(android.widget.TextView(context).apply {
                text = "$label is paused while you focus.\nGet back to what matters."
                textSize = 15f
                setTextColor(0xFFAAB2C0.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(0, px(16), 0, px(36))
            })
            addView(android.widget.Button(context).apply {
                text = "Back to work"
                setOnClickListener {
                    removeOverlay()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            })
            addView(android.widget.Button(context).apply {
                text = "End focus early"
                setOnClickListener {
                    removeOverlay()
                    TimerController.sessionId(this@WebTrackingService)?.let { id -> AppScope.io.launch { TimerController.stopSession(this@WebTrackingService, id) } }
                }
            })
        }
    }

    private fun sample() {
        val generation = historyGeneration(this)
        if (recordingGeneration != generation) {
            recordingGeneration = generation
            currentDomain = null
            sessionStart = 0
        }
        if (!screenOn || !WebsiteTrackingSettings.enabled(this)) { commitCurrent(); return }
        val root = rootInActiveWindow ?: run { commitCurrent(); return }
        val pkg = root.packageName?.toString() ?: return
        if (currentBrowser != null && currentBrowser != pkg) commitCurrent()
        val viewId = BROWSER_URL_BAR_IDS[pkg]
        if (viewId == null) {
            currentBrowser = null
            commitCurrent() // left the browser for another app
            return
        }
        currentBrowser = pkg
        val text = runCatching {
            root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull()?.text?.toString()
        }.getOrNull()
        val domain = extractDomain(text)
        if (domain == null) {
            if (text != null) commitCurrent() // visible search/new-tab address is not the old site
            return // a hidden toolbar may still display the same page
        }
        if (domain != currentDomain) {
            commitCurrent()
            currentDomain = domain
            sessionStart = System.currentTimeMillis()
            sessionElapsedStart = android.os.SystemClock.elapsedRealtime()
        }
    }

    private fun commitCurrent() {
        val domain = currentDomain ?: return
        val start = sessionStart
        currentDomain = null
        sessionStart = 0L
        if (start <= 0L) return
        val end = start + (android.os.SystemClock.elapsedRealtime() - sessionElapsedStart).coerceAtLeast(0)
        val generation = recordingGeneration
        if (end - start < 3_000L) return
        scope.launch {
            runCatching {
                val db = BiboDb.get(applicationContext)
                db.withTransaction {
                    if (generation == historyGeneration(applicationContext)) db.websites()
                        .insert(WebsiteSession(domain = domain, startMillis = start, endMillis = end))
                }
            }.onSuccess { WebsiteTrackingSettings.reportError(null) }
                .onFailure { WebsiteTrackingSettings.reportError("Website time could not be saved. Check free storage and restart collection.") }
        }
    }

    /** Commit finite slices so process loss cannot erase an entire browsing visit. */
    private fun checkpointCurrent() {
        val domain = currentDomain ?: return
        commitCurrent()
        currentDomain = domain
        sessionStart = System.currentTimeMillis()
        sessionElapsedStart = android.os.SystemClock.elapsedRealtime()
    }
}
