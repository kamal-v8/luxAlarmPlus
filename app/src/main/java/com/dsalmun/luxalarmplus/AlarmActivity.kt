/*
 * This file is part of luxAlarm+, authored by Daniel Salmun.
 *
 * luxAlarm+ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * luxAlarm+ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with luxAlarm+.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.dsalmun.luxalarmplus

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.core.net.toUri
import com.dsalmun.luxalarmplus.ui.theme.LuxAlarmTheme
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

    // Screen pinning (self-re-launch approach)
    private var lockScreenPinEnabled by mutableStateOf(SettingsManager.DEFAULT_LOCK_SCREEN_PIN)
    private var alarmDismissed = false

    // Alarm audio + vibration (owned by the activity, not a service)
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var ringtoneUri: String? = null
    private var volume: Float? = null
    private var vibrationEnabled: Boolean = true

    // Aggressive periodic relaunch handler - runs every 300ms to reclaim screen from system UI
    private val relaunchHandler = Handler(Looper.getMainLooper())
    private val relaunchRunnable = object : Runnable {
        override fun run() {
            if (!alarmDismissed && lockScreenPinEnabled) {
                forceRelaunch()
            }
            relaunchHandler.postDelayed(this, 300)
        }
    }

    // Re-hides system bars the moment they become visible, closing the "swipe down twice" trick.
    private val insetsListener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        rehideSystemBars()
    }

    private fun rehideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        }
    }

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
        ringtoneUri = intent.getStringExtra("ringtone_uri")
        volume = if (intent.hasExtra("volume")) intent.getFloatExtra("volume", 1.0f) else null
        vibrationEnabled = intent.getBooleanExtra("vibration_enabled", true)
        val settings = AppContainer.settingsManager
        requiredLightLevel = settings.getRequiredLuxLevel()
        luxHoldTimerEnabled = settings.getLuxHoldTimerEnabled()
        luxHoldDurationSeconds = settings.getLuxHoldDurationSeconds()
        lockScreenPinEnabled = settings.getLockScreenPinEnabled()

        setupScreenWake()
        setupLightSensor()
        startAlarmSound()
        startVibration()

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
        // Start the periodic handler regardless of pinning setting — it also
        // serves as a watchdog to reclaim focus from system UI.
        relaunchHandler.post(relaunchRunnable)
        setupPinning()
    }

    private fun setupLightSensor() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    }

    private fun startAlarmSound() {
        try {
            val audioAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()

            // Request audio focus to prevent system from stopping/ducking our alarm
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttrs)
                .build()
            audioManager?.requestAudioFocus(audioFocusRequest!!)

            val defaultAlarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val selectedAlarmUri = ringtoneUri?.toUri()
            mediaPlayer = createPlayerForUri(selectedAlarmUri, audioAttrs, volume)
                ?: createPlayerForUri(defaultAlarmUri, audioAttrs, volume)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start alarm sound", e)
        }
    }

    private fun createPlayerForUri(
        uri: Uri?,
        audioAttrs: AudioAttributes,
        vol: Float? = null,
    ): MediaPlayer? {
        if (uri == null) return null
        var player: MediaPlayer? = null
        return try {
            MediaPlayer().apply {
                player = this
                setDataSource(applicationContext, uri)
                setAudioAttributes(audioAttrs)
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                isLooping = true
                prepare()
                if (vol != null) setVolume(vol, vol)
                start()
            }
        } catch (e: Exception) {
            Log.w("AlarmActivity", "Failed to play ringtone URI: $uri", e)
            player?.release()
            null
        }
    }

    private fun startVibration() {
        if (!vibrationEnabled) return
        try {
            vibrator =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager =
                        getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vibratorManager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(VIBRATOR_SERVICE) as Vibrator
                }

            val vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500)
            val vibrationEffect = VibrationEffect.createWaveform(vibrationPattern, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val vibrationAttrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build()
                vibrator?.vibrate(vibrationEffect, vibrationAttrs)
            } else {
                val audioAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                @Suppress("DEPRECATION")
                vibrator?.vibrate(vibrationEffect, audioAttrs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start vibration", e)
        }
    }

    private fun releaseAlarmResources() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null

        vibrator?.cancel()
        vibrator = null

        audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        audioManager = null
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        // Re-apply immersive mode and listen for bar reappearances.
        rehideSystemBars()
        window.decorView.addOnLayoutChangeListener(insetsListener)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        window.decorView.removeOnLayoutChangeListener(insetsListener)
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
                    // Sticky immersive mode: swiping shows bars briefly, then they auto-hide.
                    // This prevents the notification shade from being pulled fully open.
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    // Hide status + navigation bars to maximize visible area
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                }
            }
        }
    }

    private fun setMaxBrightness() {
        val layoutParams = window.attributes
        layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        window.attributes = layoutParams
    }

    // ─── Screen Pinning (self-re-launch + keyguard dismissal) ────────────

    /**
     * Dismiss the keyguard so the activity shows over the lock screen.
     * On Android 12+ this is required for the activity to appear on top
     * of a PIN/pattern protected lock screen when the alarm fires.
     */
    private fun setupPinning() {
        if (!lockScreenPinEnabled) return

        // Dismiss keyguard immediately so we show over the lock screen
        try {
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                keyguardManager.requestDismissKeyguard(this, null)
            }
        } catch (e: Exception) {
            // Ignore if keyguard dismiss fails - relaunch handler will keep us on top
        }
        // Note: the periodic relaunch handler is started in onCreate regardless
        // of the pinning setting, so it also acts as a watchdog to reclaim focus.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // If we lose focus (e.g., notification shade pulled down) and alarm is still ringing,
        // immediately relaunch to regain focus
        if (!hasFocus && lockScreenPinEnabled && !alarmDismissed) {
            forceRelaunch()
        }
    }

    /** Called when the user presses the Home button. Re-launch if alarm still ringing. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (lockScreenPinEnabled && !alarmDismissed) {
            forceRelaunch()
        }
    }

    /** Called when the activity is no longer visible (e.g. swiped from Recents). */
    override fun onStop() {
        super.onStop()
        if (lockScreenPinEnabled && !alarmDismissed) {
            forceRelaunch()
        }
    }

    /**
     * Forcefully re-launch this activity on top of any system UI.
     * Uses FLAG_ACTIVITY_REORDER_TO_FRONT to avoid recreation when possible,
     * and FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP as fallback.
     * Called every 300ms by the periodic handler to stay on top of the notification shade.
     */
    private fun forceRelaunch() {
        val intent = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("alarm_id", alarmId)
            ringtoneUri?.let { putExtra("ringtone_uri", it) }
            volume?.let { putExtra("volume", it) }
            putExtra("vibration_enabled", vibrationEnabled)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // If reorder fails (rare), try without it
            Log.w(TAG, "Relaunch with REORDER_TO_FRONT failed, trying fallback", e)
            val fallbackIntent = Intent(this, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("alarm_id", alarmId)
                ringtoneUri?.let { putExtra("ringtone_uri", it) }
                volume?.let { putExtra("volume", it) }
                putExtra("vibration_enabled", vibrationEnabled)
            }
            try {
                startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.w(TAG, "Fallback relaunch also failed", e2)
            }
        }
    }

    private fun stopAlarm() {
        // Mark as dismissed BEFORE finishing so onStop won't re-launch
        alarmDismissed = true
        // Stop the periodic relaunch handler to prevent memory leaks
        relaunchHandler.removeCallbacks(relaunchRunnable)
        // Release audio and vibration resources
        releaseAlarmResources()
        // Cancel the alarm notification
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(AlarmReceiver.ALARM_NOTIFICATION_ID)
        // Cancel the watchdog — alarm is dismissed, no need to re-fire
        AlarmWatchdogReceiver.cancelWatchdog(this)
        // Clear ringing state
        AppContainer.repository.clearRingingAlarm()
        AppContainer.repository.clearRingingAlarmState()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseAlarmResources()
    }

    companion object {
        private const val TAG = "AlarmActivity"
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

