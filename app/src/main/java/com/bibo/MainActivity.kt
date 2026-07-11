package com.bibo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.VerticalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bibo.ui.CalendarScreen
import com.bibo.ui.ChatScreen
import com.bibo.ui.HealthScreen
import com.bibo.ui.StatsScreen
import com.bibo.ui.TimerScreen
import com.bibo.ui.TodoScreen
import com.bibo.ui.theme.BiboTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_TASKS = 3
        const val TAB_MENTOR = 5
    }

    // Tab requested by a notification tap; -1 means none. Consumed by BiboApp.
    private val tabRequest = mutableIntStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.bibo.data.GoalReminders.schedule(this)
        com.bibo.data.MentorCheckin.schedule(this)
        com.bibo.data.TaskReminders.rescheduleAll(this)
        tabRequest.intValue = intent?.getIntExtra(EXTRA_OPEN_TAB, -1) ?: -1
        setContent {
            BiboTheme {
                BiboApp(tabRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tabRequest.intValue = intent.getIntExtra(EXTRA_OPEN_TAB, -1)
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Calendar", Icons.Filled.CalendarMonth),
    Tab("Stats", Icons.Filled.BarChart),
    Tab("Timer", Icons.Filled.Timer),
    Tab("Tasks", Icons.Filled.Checklist),
    Tab("Daily", Icons.Filled.MonitorHeart),
    Tab("Mentor", Icons.AutoMirrored.Filled.Chat),
)

@Composable
fun BiboApp(tabRequest: MutableIntState? = null) {
    var selected by rememberSaveable { mutableIntStateOf(0) }

    // A notification tap can ask for a specific tab (e.g. mentor check-in → Mentor).
    if (tabRequest != null) {
        LaunchedEffect(tabRequest.intValue) {
            val requested = tabRequest.intValue
            if (requested in tabs.indices) {
                selected = requested
                tabRequest.intValue = -1
            }
        }
    }

    // NavigationSuiteScaffold shows a bottom bar on compact widths (folded / phone)
    // and a navigation rail when the screen is wide (Fold unfolded, landscape).
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            tabs.forEachIndexed { index, tab ->
                item(
                    selected = selected == index,
                    onClick = { selected = index },
                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                    label = { Text(tab.label) },
                )
            }
        },
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            // Unfolded (wide) screen: show Calendar and Tasks together as a two-pane
            // when either of those tabs is selected. Folded stays single-pane.
            // Threshold 600dp: the Fold's unfolded content area is ~670dp (750dp screen
            // minus the nav rail), while the folded cover screen is ~411dp.
            val twoPane = maxWidth >= 600.dp && (selected == 0 || selected == 3)
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { CalendarScreen() }
                    VerticalDivider()
                    Box(Modifier.weight(1f).fillMaxHeight()) { TodoScreen() }
                }
            } else {
                when (selected) {
                    0 -> CalendarScreen()
                    1 -> StatsScreen()
                    2 -> TimerScreen()
                    3 -> TodoScreen()
                    4 -> HealthScreen()
                    5 -> ChatScreen()
                }
            }
        }
    }
}
