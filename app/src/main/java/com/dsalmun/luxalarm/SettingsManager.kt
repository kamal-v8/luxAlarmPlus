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

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _requiredLuxLevel = MutableStateFlow(getRequiredLuxLevel())
    val requiredLuxLevel: StateFlow<Float> = _requiredLuxLevel.asStateFlow()

    private val _lockScreenPinEnabled = MutableStateFlow(getLockScreenPinEnabled())
    val lockScreenPinEnabled: StateFlow<Boolean> = _lockScreenPinEnabled.asStateFlow()

    private val _luxHoldTimerEnabled = MutableStateFlow(getLuxHoldTimerEnabled())
    val luxHoldTimerEnabled: StateFlow<Boolean> = _luxHoldTimerEnabled.asStateFlow()

    private val _luxHoldDurationSeconds = MutableStateFlow(getLuxHoldDurationSeconds())
    val luxHoldDurationSeconds: StateFlow<Int> = _luxHoldDurationSeconds.asStateFlow()

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

    fun hasPromptedForScreenPinningSetup(): Boolean {
        return prefs.getBoolean(KEY_LOCK_SCREEN_PINNING_SETUP_PROMPTED, false)
    }

    fun setLockScreenPinningSetupPrompted(prompted: Boolean) {
        prefs.edit { putBoolean(KEY_LOCK_SCREEN_PINNING_SETUP_PROMPTED, prompted) }
    }

    companion object {
        private const val PREFS_NAME = "lux_alarm_settings"
        private const val KEY_REQUIRED_LUX_LEVEL = "required_lux_level"
        private const val KEY_LOCK_SCREEN_PIN = "lock_screen_pin_enabled"
        private const val KEY_LOCK_SCREEN_PINNING_SETUP_PROMPTED = "lock_screen_pinning_setup_prompted"
        private const val KEY_LUX_HOLD_TIMER_ENABLED = "lux_hold_timer_enabled"
        private const val KEY_LUX_HOLD_DURATION = "lux_hold_duration_seconds"
        const val DEFAULT_LUX_LEVEL = 50f
        const val MIN_LUX_LEVEL = 1f
        const val MAX_LUX_LEVEL = 1000f
        const val DEFAULT_LOCK_SCREEN_PIN = true
        const val DEFAULT_LUX_HOLD_TIMER_ENABLED = false
        const val DEFAULT_LUX_HOLD_DURATION = 15
        const val MIN_LUX_HOLD_DURATION = 5
        const val MAX_LUX_HOLD_DURATION = 120
    }
}
