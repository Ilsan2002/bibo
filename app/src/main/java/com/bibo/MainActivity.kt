package com.bibo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.bibo.ui.CalendarScreen
import com.bibo.ui.HealthScreen
import com.bibo.ui.StatsScreen
import com.bibo.ui.TimerScreen
import com.bibo.ui.TodoScreen
import com.bibo.ui.theme.BiboTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BiboTheme {
                BiboApp()
            }
        }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Calendar", Icons.Filled.CalendarMonth),
    Tab("Stats", Icons.Filled.BarChart),
    Tab("Timer", Icons.Filled.Timer),
    Tab("Tasks", Icons.Filled.Checklist),
    Tab("Daily", Icons.Filled.MonitorHeart),
)

@Composable
fun BiboApp() {
    var selected by rememberSaveable { mutableIntStateOf(0) }

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
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            when (selected) {
                0 -> CalendarScreen()
                1 -> StatsScreen()
                2 -> TimerScreen()
                3 -> TodoScreen()
                4 -> HealthScreen()
            }
        }
    }
}
