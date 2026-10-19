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
package com.dsalmun.luxalarmplus.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device-protected (DE) mirror of the alarm schedule and of the ringing state.
 *
 * The Room database lives in credential-encrypted storage, which is only
 * readable once the user has unlocked the device. Components declared
 * `android:directBootAware="true"` run *before* that first unlock, so they
 * need a copy of the schedule that lives in device-protected storage
 * instead. This store is that copy.
 *
 * Every getter is defensive: a missing or corrupted entry yields an empty
 * list or `null` instead of throwing, because these are read from receivers
 * that must never crash.
 */
class DeviceProtectedAlarmStore(context: Context) {
    private val prefs: SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── Schedule snapshot ────────────────────────────────────────────────

    /** Mirrors the currently active alarms so they are known before unlock. */
    fun saveActiveAlarms(alarms: List<AlarmItem>) {
        val array = JSONArray()
        alarms.forEach { array.put(alarmToJson(it)) }
        // commit() and not apply(): the process of a broadcast receiver is
        // killed right after it returns, an asynchronous write would be lost.
        prefs.edit(commit = true) { putString(KEY_ACTIVE_ALARMS, array.toString()) }
    }

    /** The active alarms as mirrored by [saveActiveAlarms]. */
    fun loadActiveAlarms(): List<AlarmItem> {
        val raw = prefs.getString(KEY_ACTIVE_ALARMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val alarms = ArrayList<AlarmItem>(array.length())
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                alarmFromJson(obj)?.let { alarms.add(it) }
            }
            alarms
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read the device-protected alarm snapshot", e)
            emptyList()
        }
    }

    // ─── Ringing state ────────────────────────────────────────────────────

    fun setRinging(ringing: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY_IS_RINGING, ringing) }
    }

    fun isRinging(): Boolean = prefs.getBoolean(KEY_IS_RINGING, false)

    fun saveRingingState(state: RingingAlarmState) {
        prefs.edit(commit = true) {
            putInt(KEY_RINGING_ALARM_ID, state.alarmId)
            putString(KEY_RINGING_RINGTONE_URI, state.ringtoneUri)
            if (state.volume != null) putFloat(KEY_RINGING_VOLUME, state.volume)
            else remove(KEY_RINGING_VOLUME)
            putBoolean(KEY_RINGING_VIBRATION_ENABLED, state.vibrationEnabled)
        }
    }

    fun loadRingingState(): RingingAlarmState? {
        if (!prefs.contains(KEY_RINGING_ALARM_ID)) return null
        val alarmId = prefs.getInt(KEY_RINGING_ALARM_ID, -1)
        if (alarmId < 0) return null
        val ringtoneUri = prefs.getString(KEY_RINGING_RINGTONE_URI, null)
        val volume =
            if (prefs.contains(KEY_RINGING_VOLUME)) prefs.getFloat(KEY_RINGING_VOLUME, 1f) else null
        val vibrationEnabled = prefs.getBoolean(KEY_RINGING_VIBRATION_ENABLED, true)
        return RingingAlarmState(alarmId, ringtoneUri, volume, vibrationEnabled)
    }

    fun clearRingingState() {
        prefs.edit(commit = true) {
            remove(KEY_RINGING_ALARM_ID)
            remove(KEY_RINGING_RINGTONE_URI)
            remove(KEY_RINGING_VOLUME)
            remove(KEY_RINGING_VIBRATION_ENABLED)
        }
    }

    // ─── Deferred one-shot deactivations ──────────────────────────────────

    /**
     * Records one-shot alarms that fired while the device was locked and
     * therefore could not be deactivated in Room yet.
     */
    fun addPendingDeactivations(ids: List<Int>) {
        if (ids.isEmpty()) return
        val pending = pendingDeactivationIds().toMutableSet()
        pending.addAll(ids)
        prefs.edit(commit = true) {
            putStringSet(KEY_PENDING_DEACTIVATIONS, pending.map { it.toString() }.toSet())
        }
    }

    /** Returns and clears the pending deactivations. */
    fun consumePendingDeactivations(): List<Int> {
        val pending = pendingDeactivationIds()
        prefs.edit(commit = true) { remove(KEY_PENDING_DEACTIVATIONS) }
        return pending
    }

    private fun pendingDeactivationIds(): List<Int> {
        val stored = prefs.getStringSet(KEY_PENDING_DEACTIVATIONS, emptySet()) ?: emptySet()
        return stored.mapNotNull { it.toIntOrNull() }
    }

    // ─── Serialisation ────────────────────────────────────────────────────

    private fun alarmToJson(alarm: AlarmItem): JSONObject =
        JSONObject().apply {
            put("id", alarm.id)
            put("hour", alarm.hour)
            put("minute", alarm.minute)
            put("isActive", alarm.isActive)
            put("repeatDays", JSONArray(alarm.repeatDays.toList()))
            put("ringtoneUri", alarm.ringtoneUri ?: JSONObject.NULL)
            put("volume", alarm.volume?.toDouble() ?: JSONObject.NULL)
            put("vibrationEnabled", alarm.vibrationEnabled)
        }

    private fun alarmFromJson(json: JSONObject): AlarmItem? {
        val id = json.optInt("id", -1)
        if (id < 0) return null
        val repeatDaysArray = json.optJSONArray("repeatDays")
        val repeatDays =
            if (repeatDaysArray == null) {
                emptySet()
            } else {
                buildSet {
                    for (index in 0 until repeatDaysArray.length()) {
                        add(repeatDaysArray.optInt(index))
                    }
                }
            }
        val ringtoneUri = if (json.isNull("ringtoneUri")) null else json.optString("ringtoneUri")
        val volume = if (json.isNull("volume")) null else json.optDouble("volume", 0.0).toFloat()
        return AlarmItem(
            id = id,
            hour = json.optInt("hour", 0),
            minute = json.optInt("minute", 0),
            isActive = json.optBoolean("isActive", true),
            repeatDays = repeatDays,
            ringtoneUri = ringtoneUri,
            volume = volume,
            vibrationEnabled = json.optBoolean("vibrationEnabled", true),
        )
    }

    companion object {
        private const val TAG = "DeviceProtectedStore"
        private const val PREFS_NAME = "device_protected_alarms"
        private const val KEY_ACTIVE_ALARMS = "active_alarms"
        private const val KEY_IS_RINGING = "is_ringing"
        private const val KEY_RINGING_ALARM_ID = "ringing_alarm_id"
        private const val KEY_RINGING_RINGTONE_URI = "ringing_ringtone_uri"
        private const val KEY_RINGING_VOLUME = "ringing_volume"
        private const val KEY_RINGING_VIBRATION_ENABLED = "ringing_vibration_enabled"
        private const val KEY_PENDING_DEACTIVATIONS = "pending_deactivations"
    }
}
