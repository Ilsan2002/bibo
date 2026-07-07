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
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            AppInfo(pkg, ri.loadLabel(pm).toString())
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
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
    private var lastUrlSample = 0L

    private var overlay: android.view.View? = null
    private var overlayForPkg: String? = null

    private val systemPackages = setOf(
        packageName, "com.android.systemui",
        "com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher",
    )

    // Runs faster while a focus session is actively blocking, so a blocked app is covered
    // within ~1s; otherwise it just ticks the URL sampler every 15s.
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now - lastUrlSample >= 15_000L) {
                sample()
                lastUrlSample = now
            }
            val blocking = blockingActive()
            if (blocking) enforceBlock() else removeOverlay()
            handler.postDelayed(this, if (blocking) 1_200L else 15_000L)
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) commitCurrent()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Events don't fire reliably on all OEMs (One UI), so the periodic tick is the
        // primary sampler / blocker; events, when they arrive, refine it.
        handler.postDelayed(tick, 3_000L)
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        sample()
        if (blockingActive()) enforceBlock()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tick)
        removeOverlay()
        runCatching { unregisterReceiver(screenOffReceiver) }
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
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return
        if (pkg in systemPackages) {
            removeOverlay()
            return
        }
        if (pkg in TimerController.blockedApps(this)) showOverlay(pkg) else removeOverlay()
    }

    private fun showOverlay(pkg: String) {
        if (overlay != null && overlayForPkg == pkg) return
        if (!Settings.canDrawOverlays(this)) {
            performGlobalAction(GLOBAL_ACTION_HOME) // fallback: bounce to home
            return
        }
        removeOverlay()
        val view = buildOverlay(pkg)
        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
        runCatching {
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).addView(view, params)
            overlay = view
            overlayForPkg = pkg
        }
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
                    TimerController.stopTimer(this@WebTrackingService)
                }
            })
        }
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
