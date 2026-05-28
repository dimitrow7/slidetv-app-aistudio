package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import com.example.data.prefs.SignagePrefs
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    composeTestRule.setContent { MyApplicationTheme { Greeting("Robolectric") } }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }

  @Test
  fun settings_dialog_rendering() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = SignagePrefs(context)
    composeTestRule.setContent {
      MyApplicationTheme {
        SettingsDialog(
          prefs = prefs,
          isScheduleEnabled = true,
          onScheduleEnabledChanged = {},
          sleepHour = 22,
          onSleepHourChanged = {},
          sleepMinute = 0,
          onSleepMinuteChanged = {},
          wakeHour = 8,
          onWakeHourChanged = {},
          wakeMinute = 0,
          onWakeMinuteChanged = {},
          onClearCache = {},
          onDisconnectDevice = {},
          onReload = {},
          onWatchdogChanged = {},
          onDismiss = {}
        )
      }
    }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/settings_dialog.png")
  }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier)
}
