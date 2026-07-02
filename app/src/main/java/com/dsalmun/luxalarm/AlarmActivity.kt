/*
 * This file is part of Lux Alarm, authored by Daniel Salmun.
 *
 * Lux Alarm is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Lux Alarm is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Lux Alarm.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.dsalmun.luxalarm

import android.app.KeyguardManager
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import java.text.SimpleDateFormat
import java.util.*

class AlarmActivity : ComponentActivity(), SensorEventListener {

    private var alarmId: Int = -1
    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private var currentLightLevel by mutableFloatStateOf(0f)
    private var requiredLightLevel by mutableFloatStateOf(SettingsManager.DEFAULT_LUX_LEVEL)

    // Lux hold timer state
    private var luxHoldTimerEnabled by mutableStateOf(false)
    private var luxHoldDurationSeconds by mutableIntStateOf(SettingsManager.DEFAULT_LUX_HOLD_DURATION)
    private var holdElapsedSeconds by mutableFloatStateOf(0f)
    private var luxAboveThresholdSince: Long? = null

    // Lock screen pinning
    private var lockScreenPinEnabled by mutableStateOf(SettingsManager.DEFAULT_LOCK_SCREEN_PIN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    /* block back press while alarm is ringing */
                }
            },
        )

        alarmId = intent.getIntExtra("alarm_id", -1)
        val settings = AppContainer.settingsManager
        requiredLightLevel = settings.getRequiredLuxLevel()
        luxHoldTimerEnabled = settings.getLuxHoldTimerEnabled()
        luxHoldDurationSeconds = settings.getLuxHoldDurationSeconds()
        lockScreenPinEnabled = settings.getLockScreenPinEnabled()

        setupScreenWake()
        setupLightSensor()

        setContent {
            LuxAlarmTheme {
                AlarmRingingScreen(
                    currentLightLevel = currentLightLevel,
                    requiredLightLevel = requiredLightLevel,
                    luxHoldTimerEnabled = luxHoldTimerEnabled,
                    luxHoldDurationSeconds = luxHoldDurationSeconds,
                    holdElapsedSeconds = holdElapsedSeconds,
                    onStopAlarm = { stopAlarm() },
                )
            }
        }
        setupFullscreen()
    }

    private fun setupLightSensor() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            currentLightLevel = event.values[0]

            // Track lux hold timer
            if (luxHoldTimerEnabled) {
                if (currentLightLevel >= requiredLightLevel) {
                    val now = System.currentTimeMillis()
                    if (luxAboveThresholdSince == null) {
                        luxAboveThresholdSince = now
                    }
                    holdElapsedSeconds =
                        ((now - luxAboveThresholdSince!!) / 1000f)
                            .coerceAtMost(luxHoldDurationSeconds.toFloat())
                } else {
                    // Light dropped below threshold — reset timer
                    luxAboveThresholdSince = null
                    holdElapsedSeconds = 0f
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // No action needed for light sensor accuracy changes
    }

    private fun setupScreenWake() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Max brightness for alarm visibility
        setMaxBrightness()
    }

    private fun setupFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.post {
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.statusBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
    }

    private fun setMaxBrightness() {
        val layoutParams = window.attributes
        layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        window.attributes = layoutParams
    }

    /** Called when window gains or loses focus. Start pinning only after we actually have focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && lockScreenPinEnabled) {
            dismissKeyguardAndLockTask()
        }
    }

    /** Prevent the user from leaving cleanly via home/recent apps buttons. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (lockScreenPinEnabled) {
            // User tried to leave; re-acquire focus and re-attempt lock task
            window.decorView.postDelayed({ dismissKeyguardAndLockTask() }, 250)
        }
    }

    private fun dismissKeyguardAndLockTask() {
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                keyguardManager.requestDismissKeyguard(
                    this,
                    object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() {
                            Log.i("AlarmActivity", "Keyguard dismissed — trying lock task")
                            tryStartLockTask()
                        }
                        override fun onDismissError() {
                            Log.w("AlarmActivity", "Keyguard dismiss error — trying lock task anyway")
                            tryStartLockTask()
                        }
                    }
                )
            } else {
                tryStartLockTask()
            }
        } else {
            // Device already unlocked — go straight to lock task
            tryStartLockTask()
        }
    }

    private fun tryStartLockTask() {
        try {
            startLockTask()
            Log.i("AlarmActivity", "Lock task started successfully")
        } catch (e: Exception) {
            Log.w("AlarmActivity", "startLockTask failed, retrying in 750ms", e)
            // Retry after a short delay — sometimes needs another focus cycle
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    startLockTask()
                    Log.i("AlarmActivity", "Lock task succeeded on retry")
                } catch (e2: Exception) {
                    Log.e("AlarmActivity", "Lock task failed — ensure Settings > Security > Screen Pinning is enabled.", e2)
                }
            }, 750)
        }
    }

    private fun stopAlarm() {
        if (lockScreenPinEnabled) {
            try {
                stopLockTask()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val stopIntent =
            Intent(this, AlarmService::class.java).apply {
                action = AlarmService.ACTION_STOP_ALARM
                putExtra("alarm_id", alarmId)
            }
        startService(stopIntent)
        finish()
    }
}

@Composable
fun AlarmRingingScreen(
    currentLightLevel: Float,
    requiredLightLevel: Float,
    luxHoldTimerEnabled: Boolean,
    luxHoldDurationSeconds: Int,
    holdElapsedSeconds: Float,
    onStopAlarm: () -> Unit,
) {
    val currentTime = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    val currentDate = remember {
        SimpleDateFormat("EEEE, MMMM dd", Locale.getDefault()).format(Date())
    }
    val greeting = remember { getTimeBasedGreeting() }

    val gradientColors =
        listOf(
            Color(0xFF6366F1), // Soft indigo
            Color(0xFF8B5CF6), // Soft purple
            Color(0xFFA855F7), // Light purple
        )

    val luxMeetsThreshold = currentLightLevel >= requiredLightLevel
    val holdTimerComplete =
        if (luxHoldTimerEnabled) holdElapsedSeconds >= luxHoldDurationSeconds
        else true
    val isButtonEnabled = luxMeetsThreshold && holdTimerComplete

    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(
                    brush =
                        Brush.verticalGradient(
                            colors = gradientColors,
                            startY = 0f,
                            endY = Float.POSITIVE_INFINITY,
                        )
                ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TimeDisplay(greeting, currentDate, currentTime)
            Spacer(modifier = Modifier.height(48.dp))
            LightSensorIndicator(
                currentLightLevel = currentLightLevel,
                requiredLightLevel = requiredLightLevel,
                luxMeetsThreshold = luxMeetsThreshold,
                luxHoldTimerEnabled = luxHoldTimerEnabled,
                luxHoldDurationSeconds = luxHoldDurationSeconds,
                holdElapsedSeconds = holdElapsedSeconds,
                holdTimerComplete = holdTimerComplete,
            )
            AlarmControlButton(isButtonEnabled, onStopAlarm)
        }
    }
}

@Composable
private fun TimeDisplay(greeting: String, currentDate: String, currentTime: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = greeting,
            fontSize = 32.sp,
            fontWeight = FontWeight.Light,
            color = Color.White.copy(alpha = 0.9f),
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = currentDate,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = currentTime,
            fontSize = 64.sp,
            fontWeight = FontWeight.Light,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LightSensorIndicator(
    currentLightLevel: Float,
    requiredLightLevel: Float,
    luxMeetsThreshold: Boolean,
    luxHoldTimerEnabled: Boolean,
    luxHoldDurationSeconds: Int,
    holdElapsedSeconds: Float,
    holdTimerComplete: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(0.8f).padding(bottom = 24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Light Level",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                text = "${currentLightLevel.toInt()} lx",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = if (luxMeetsThreshold) Color(0xFF10B981) else Color.White,
            )
            Text(
                text =
                    if (luxMeetsThreshold) "Bright enough!"
                    else "Need ${requiredLightLevel.toInt()} lx minimum",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            if (!luxMeetsThreshold) {
                Text(
                    text = "Go to a brighter area to turn off alarm",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Hold timer progress
            if (luxHoldTimerEnabled) {
                Spacer(modifier = Modifier.height(12.dp))

                val progress =
                    if (luxHoldDurationSeconds > 0)
                        (holdElapsedSeconds / luxHoldDurationSeconds).coerceIn(0f, 1f)
                    else 1f
                val remainingSeconds =
                    (luxHoldDurationSeconds - holdElapsedSeconds).coerceAtLeast(0f).toInt()

                val animatedProgress by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = tween(durationMillis = 300),
                    label = "holdProgress",
                )

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color =
                        if (holdTimerComplete) Color(0xFF10B981)
                        else Color(0xFFFBBF24),
                    trackColor = Color.White.copy(alpha = 0.2f),
                    strokeCap = StrokeCap.Round,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text =
                        when {
                            holdTimerComplete -> "✓ Hold complete — you can dismiss now"
                            luxMeetsThreshold -> "Hold steady… ${remainingSeconds}s remaining"
                            else -> "Keep light above ${requiredLightLevel.toInt()} lx for ${luxHoldDurationSeconds}s"
                        },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color =
                        when {
                            holdTimerComplete -> Color(0xFF10B981)
                            luxMeetsThreshold -> Color(0xFFFBBF24)
                            else -> Color.White.copy(alpha = 0.6f)
                        },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AlarmControlButton(isButtonEnabled: Boolean, onStopAlarm: () -> Unit) {
    ElevatedButton(
        onClick = onStopAlarm,
        enabled = isButtonEnabled,
        modifier = Modifier.fillMaxWidth(0.6f).height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors =
            ButtonDefaults.elevatedButtonColors(
                containerColor =
                    if (isButtonEnabled) Color.White.copy(alpha = 0.95f)
                    else Color.Gray.copy(alpha = 0.5f),
                contentColor =
                    if (isButtonEnabled) Color(0xFF6366F1) else Color.White.copy(alpha = 0.6f),
                disabledContainerColor = Color.Gray.copy(alpha = 0.3f),
                disabledContentColor = Color.White.copy(alpha = 0.4f),
            ),
        elevation =
            ButtonDefaults.elevatedButtonElevation(
                defaultElevation = if (isButtonEnabled) 8.dp else 2.dp,
                pressedElevation = if (isButtonEnabled) 12.dp else 2.dp,
                disabledElevation = 0.dp,
            ),
    ) {
        Text(
            text = if (isButtonEnabled) "Turn Off Alarm" else "Need More Light",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun getTimeBasedGreeting(): String {
    val calendar = Calendar.getInstance()
    val hour = calendar[Calendar.HOUR_OF_DAY]

    return when (hour) {
        in 5..11 -> "Good Morning"
        in 12..17 -> "Good Afternoon"
        in 18..21 -> "Good Evening"
        else -> "Time to Wake Up" // Late night/early morning (22-4)
    }
}

