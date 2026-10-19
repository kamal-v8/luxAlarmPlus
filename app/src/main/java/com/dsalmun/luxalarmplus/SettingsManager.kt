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

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user settings.
 *
 * The settings live in device-protected storage so that they are readable
 * before the user has unlocked the device: the alarm screen may be shown
 * while the device is still locked.
 *
 * None of the settings are sensitive — the "screen pinning" setting is only
 * a flag that tells the app to enable Android's system screen pinning, no
 * secret is stored.
 */
class SettingsManager(context: Context) {
    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _requiredLuxLevel = MutableStateFlow(getRequiredLuxLevel())
    val requiredLuxLevel: StateFlow<Float> = _requiredLuxLevel.asStateFlow()

    private val _lockScreenPinEnabled = MutableStateFlow(getLockScreenPinEnabled())
    val lockScreenPinEnabled: StateFlow<Boolean> = _lockScreenPinEnabled.asStateFlow()

    private val _luxHoldTimerEnabled = MutableStateFlow(getLuxHoldTimerEnabled())
    val luxHoldTimerEnabled: StateFlow<Boolean> = _luxHoldTimerEnabled.asStateFlow()

    private val _luxHoldDurationSeconds = MutableStateFlow(getLuxHoldDurationSeconds())
    val luxHoldDurationSeconds: StateFlow<Int> = _luxHoldDurationSeconds.asStateFlow()

    init {
        migrateLegacyPreferences()
    }

    fun getRequiredLuxLevel(): Float {
        return prefs.getFloat(KEY_REQUIRED_LUX_LEVEL, DEFAULT_LUX_LEVEL)
    }

    fun setRequiredLuxLevel(level: Float) {
        prefs.edit { putFloat(KEY_REQUIRED_LUX_LEVEL, level) }
        _requiredLuxLevel.value = level
    }

    fun getLockScreenPinEnabled(): Boolean {
        return prefs.getBoolean(KEY_LOCK_SCREEN_PIN, DEFAULT_LOCK_SCREEN_PIN)
    }

    fun setLockScreenPinEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_LOCK_SCREEN_PIN, enabled) }
        _lockScreenPinEnabled.value = enabled
    }

    fun getLuxHoldTimerEnabled(): Boolean {
        return prefs.getBoolean(KEY_LUX_HOLD_TIMER_ENABLED, DEFAULT_LUX_HOLD_TIMER_ENABLED)
    }

    fun setLuxHoldTimerEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_LUX_HOLD_TIMER_ENABLED, enabled) }
        _luxHoldTimerEnabled.value = enabled
    }

    fun getLuxHoldDurationSeconds(): Int {
        return prefs.getInt(KEY_LUX_HOLD_DURATION, DEFAULT_LUX_HOLD_DURATION)
    }

    fun setLuxHoldDurationSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(MIN_LUX_HOLD_DURATION, MAX_LUX_HOLD_DURATION)
        prefs.edit { putInt(KEY_LUX_HOLD_DURATION, clamped) }
        _luxHoldDurationSeconds.value = clamped
    }

    /**
     * The settings used to live in credential-encrypted storage, which is
     * only readable after the first unlock. Copy the values that are still
     * missing from the old location into the device-protected one.
     *
     * Called again once the user unlocks the device, in case the app was
     * started in the locked (direct boot) state.
     */
    fun migrateLegacyPreferences() {
        try {
            val userManager = appContext.getSystemService(Context.USER_SERVICE) as UserManager
            if (!userManager.isUserUnlocked()) return
            val legacy = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var migrated = false
            for (key in LEGACY_KEYS) {
                if (prefs.contains(key) || !legacy.contains(key)) continue
                when (key) {
                    KEY_REQUIRED_LUX_LEVEL ->
                        prefs.edit { putFloat(key, legacy.getFloat(key, DEFAULT_LUX_LEVEL)) }
                    KEY_LUX_HOLD_DURATION ->
                        prefs.edit { putInt(key, legacy.getInt(key, DEFAULT_LUX_HOLD_DURATION)) }
                    else ->
                        prefs.edit { putBoolean(key, legacy.getBoolean(key, false)) }
                }
                migrated = true
            }
            if (migrated) {
                Log.i(TAG, "Migrated the settings to device-protected storage")
                _requiredLuxLevel.value = getRequiredLuxLevel()
                _lockScreenPinEnabled.value = getLockScreenPinEnabled()
                _luxHoldTimerEnabled.value = getLuxHoldTimerEnabled()
                _luxHoldDurationSeconds.value = getLuxHoldDurationSeconds()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate the settings to device-protected storage", e)
        }
    }

    companion object {
        private const val TAG = "SettingsManager"
        private const val PREFS_NAME = "lux_alarm_settings"
        private const val KEY_REQUIRED_LUX_LEVEL = "required_lux_level"
        private const val KEY_LOCK_SCREEN_PIN = "lock_screen_pin_enabled"
        private const val KEY_LUX_HOLD_TIMER_ENABLED = "lux_hold_timer_enabled"
        private const val KEY_LUX_HOLD_DURATION = "lux_hold_duration_seconds"
        private val LEGACY_KEYS =
            listOf(
                KEY_REQUIRED_LUX_LEVEL,
                KEY_LOCK_SCREEN_PIN,
                KEY_LUX_HOLD_TIMER_ENABLED,
                KEY_LUX_HOLD_DURATION,
            )
        const val DEFAULT_LUX_LEVEL = 50f
        const val MIN_LUX_LEVEL = 1f
        const val MAX_LUX_LEVEL = 1000f
        const val DEFAULT_LOCK_SCREEN_PIN = false
        const val DEFAULT_LUX_HOLD_TIMER_ENABLED = false
        const val DEFAULT_LUX_HOLD_DURATION = 15
        const val MIN_LUX_HOLD_DURATION = 5
        const val MAX_LUX_HOLD_DURATION = 120
    }
}
