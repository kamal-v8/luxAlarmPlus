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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.core.content.edit
import com.dsalmun.luxalarmplus.AlarmReceiver
import com.dsalmun.luxalarmplus.MainActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The alarm schedule and the ringing state.
 *
 * Room is the source of truth for the schedule, but it lives in
 * credential-encrypted storage and is therefore unreadable before the user
 * unlocks the device. Every change is mirrored into [DeviceProtectedAlarmStore]
 * so that direct-boot aware components (boot receiver, alarm receiver,
 * watchdog) can still know the schedule and re-arm the alarms.
 *
 * All scheduling methods work in both states: unlocked they read and write
 * Room, locked they fall back to the device-protected snapshot.
 */
class AlarmRepository(
    private val context: Context,
    private val daoProvider: () -> AlarmDao? = { AlarmDatabase.getDatabase(context).alarmDao() },
    private val unlockedProvider: () -> Boolean = { isUserUnlocked(context) },
) : IAlarmRepository {
    private companion object {
        const val TAG = "AlarmRepository"
        const val NEXT_ALARM_REQUEST_CODE = 0
        const val MISSED_ALARM_REQUEST_CODE = 1
        const val PREFS_NAME = "alarm_state"
        const val KEY_V1_MIGRATED = "v1_migrated"

        /** Whether the credential-encrypted storage is readable right now. */
        fun isUserUnlocked(context: Context): Boolean {
            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            return userManager.isUserUnlocked()
        }
    }

    private val deviceStore = DeviceProtectedAlarmStore(context)
    private val stateStore = AlarmStateStore(context)

    override fun getAllAlarms(): Flow<List<AlarmItem>> {
        val dao = currentDao()
        return dao?.getAllAlarms() ?: flowOf(deviceStore.loadActiveAlarms())
    }

    override suspend fun addAlarm(hour: Int, minute: Int): Boolean {
        val dao = currentDao() ?: return false
        val newAlarm = AlarmItem(hour = hour, minute = minute)
        val newId = dao.insert(newAlarm)

        if (!scheduleNextAlarm()) {
            dao.delete(newAlarm.copy(id = newId.toInt()))
            refreshMirror()
            return false
        }
        return true
    }

    override suspend fun toggleAlarm(alarmId: Int, isActive: Boolean): Boolean {
        val dao = currentDao() ?: return false
        val alarm = dao.getAlarmById(alarmId) ?: return true
        val updatedAlarm = alarm.copy(isActive = isActive)
        dao.update(updatedAlarm)

        if (!scheduleNextAlarm()) {
            // Permission denied - revert the change and signal failure
            dao.update(alarm)
            refreshMirror()
            return false
        }
        return true
    }

    override suspend fun updateAlarmTime(alarmId: Int, hour: Int, minute: Int): Boolean {
        val dao = currentDao() ?: return false
        val alarm = dao.getAlarmById(alarmId) ?: return true
        val updatedAlarm = alarm.copy(hour = hour, minute = minute, isActive = true)
        dao.update(updatedAlarm)

        if (!scheduleNextAlarm()) {
            // Permission denied - revert the change and signal failure
            dao.update(alarm)
            refreshMirror()
            return false
        }
        return true
    }

    override suspend fun deleteAlarm(alarmId: Int) {
        val dao = currentDao() ?: return
        dao.getAlarmById(alarmId)?.let { alarm ->
            dao.delete(alarm)
            scheduleNextAlarm()
            refreshMirror()
        }
    }

    override suspend fun setRepeatDays(alarmId: Int, repeatDays: Set<Int>) {
        val dao = currentDao() ?: return
        val alarm = dao.getAlarmById(alarmId) ?: return
        val updatedAlarm = alarm.copy(repeatDays = repeatDays)
        dao.update(updatedAlarm)
        scheduleNextAlarm()
        refreshMirror()
    }

    override suspend fun setAlarmRingtone(alarmId: Int, ringtoneUri: String?) {
        val dao = currentDao() ?: return
        val alarm = dao.getAlarmById(alarmId) ?: return
        val updatedAlarm = alarm.copy(ringtoneUri = ringtoneUri)
        dao.update(updatedAlarm)
        // Reschedule: ringtone URI is embedded in the PendingIntent extras
        scheduleNextAlarm()
        refreshMirror()
    }

    override suspend fun setAlarmVolume(alarmId: Int, volume: Float?) {
        val dao = currentDao() ?: return
        val alarm = dao.getAlarmById(alarmId) ?: return
        val updatedAlarm = alarm.copy(volume = volume?.coerceIn(0f, 1f))
        dao.update(updatedAlarm)
        // Reschedule: volume is embedded in the PendingIntent extras
        scheduleNextAlarm()
        refreshMirror()
    }

    override suspend fun setAlarmVibration(alarmId: Int, enabled: Boolean) {
        val dao = currentDao() ?: return
        val alarm = dao.getAlarmById(alarmId) ?: return
        val updatedAlarm = alarm.copy(vibrationEnabled = enabled)
        dao.update(updatedAlarm)
        // Reschedule: vibration setting is embedded in the PendingIntent extras
        scheduleNextAlarm()
        refreshMirror()
    }

    override suspend fun scheduleNextAlarm(): Boolean = scheduleNext(cancelMissedRetry = true)

    override suspend fun rescheduleAfterBoot(): Boolean {
        // An alarm that rang while the device was locked could not be
        // deactivated in Room yet, the deactivation is pending. Apply it
        // first, otherwise the alarm that just rang would look like a missed
        // one and would ring a second time.
        applyPendingDeactivations()

        // Unlocked this reads Room, locked it reads the device-protected
        // snapshot. Both are the same list of active alarms.
        val activeAlarms = loadActiveAlarms()

        if (activeAlarms.isEmpty()) {
            cancelNextAlarm()
            cancelMissedAlarm()
            refreshMirror()
            return true
        }

        if (!canScheduleExactAlarms()) {
            Log.w(TAG, "rescheduleAfterBoot: cannot schedule exact alarms, permission missing")
            return false
        }

        val now = System.currentTimeMillis()
        val missed = AlarmScheduleCalculator.findMissedAlarms(activeAlarms, now)

        if (missed.isEmpty()) {
            Log.i(TAG, "rescheduleAfterBoot: no missed alarms, scheduling next")
            return scheduleNext(cancelMissedRetry = true)
        }

        val sortedMissed = missed.sortedBy { it.id }
        val retryAt = now + IAlarmRepository.REBOOT_GRACE_DELAY_MS
        Log.i(
            TAG,
            "rescheduleAfterBoot: ${sortedMissed.size} missed alarm(s) " +
                "${sortedMissed.map { it.id }}, retrying at $retryAt",
        )
        // Schedule the future alarm in the normal slot first, then the missed
        // retry in its own slot. Separate request codes let both coexist, so a
        // reboot doesn't lose alarms after the missed one. The retry fire
        // chain-schedules via AlarmReceiver -> scheduleNextAlarm().
        val nextSlot = AlarmScheduleCalculator.nextTriggerSlot(activeAlarms, now)
        scheduleAlarmAt(nextSlot.triggerAtMillis, nextSlot.alarms, NEXT_ALARM_REQUEST_CODE)
        scheduleAlarmAt(retryAt, sortedMissed, MISSED_ALARM_REQUEST_CODE)
        refreshMirror()
        return true
    }

    /**
     * Applies the work that could not be done while the device was locked and
     * re-syncs the device-protected mirror.
     *
     * Called from [com.dsalmun.luxalarmplus.UserUnlockedReceiver] as soon as
     * the user unlocks the device.
     */
    override suspend fun syncAfterUnlock() {
        val dao = currentDao()
        if (dao == null) {
            Log.w(TAG, "syncAfterUnlock: device is still locked, nothing to sync")
            return
        }

        applyPendingDeactivations()

        refreshMirror()
        // Re-schedule from the now authoritative Room data. A pending
        // missed-alarm retry is deliberately kept alive.
        scheduleNext(cancelMissedRetry = false)
    }

    /**
     * Applies the one-shot deactivations that were deferred while the device
     * was locked. Does nothing while the device is still locked, because Room
     * is not readable then.
     */
    private suspend fun applyPendingDeactivations() {
        val dao = currentDao() ?: return
        val pending = deviceStore.consumePendingDeactivations()
        if (pending.isEmpty()) return
        Log.i(TAG, "applying ${pending.size} pending deactivation(s) $pending")
        try {
            dao.deactivateOneShotAlarms(pending)
        } catch (e: Exception) {
            Log.w(TAG, "failed to apply the pending deactivations", e)
            // Keep them pending, the next attempt has to try again.
            deviceStore.addPendingDeactivations(pending)
        }
    }

    override fun isUserUnlocked(): Boolean =
        try {
            unlockedProvider()
        } catch (e: Exception) {
            Log.w(TAG, "isUserUnlocked: failed to query the UserManager", e)
            false
        }

    private suspend fun scheduleNext(cancelMissedRetry: Boolean): Boolean {
        val activeAlarms = loadActiveAlarms()

        if (activeAlarms.isEmpty()) {
            cancelNextAlarm()
            if (cancelMissedRetry) cancelMissedAlarm()
            refreshMirror()
            return true
        }

        if (!canScheduleExactAlarms()) {
            Log.w(TAG, "scheduleNextAlarm: cannot schedule exact alarms, permission missing")
            return false
        }

        val now = System.currentTimeMillis()
        val nextSlot = AlarmScheduleCalculator.nextTriggerSlot(activeAlarms, now)
        scheduleAlarmAt(nextSlot.triggerAtMillis, nextSlot.alarms, NEXT_ALARM_REQUEST_CODE)
        // A normal schedule supersedes any stale missed-alarm retry.
        if (cancelMissedRetry) cancelMissedAlarm()
        refreshMirror()
        return true
    }

    private fun scheduleAlarmAt(
        triggerAtMillis: Long,
        alarmsInSlot: List<AlarmItem>,
        requestCode: Int,
    ) {
        val sorted = alarmsInSlot.sortedBy { it.id }
        val alarmIds = sorted.map { it.id }
        // When multiple alarms fire simultaneously, use ringtone from lowest-ID alarm
        val nextAlarm = sorted.first()

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent =
            Intent(context, AlarmReceiver::class.java).apply {
                putIntegerArrayListExtra("alarm_ids", ArrayList(alarmIds))
                putExtra("ringtone_uri", nextAlarm.ringtoneUri)
                nextAlarm.volume?.let { putExtra("volume", it) }
                putExtra("vibration_enabled", nextAlarm.vibrationEnabled)
            }
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val showIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
            pendingIntent,
        )
    }

    /**
     * The active alarms, from Room when it is readable and from the
     * device-protected snapshot while the device is locked.
     */
    private suspend fun loadActiveAlarms(): List<AlarmItem> {
        val dao = currentDao()
        if (dao != null) {
            try {
                val alarms = dao.getActiveAlarms()
                Log.i(TAG, "loadActiveAlarms: $alarms from Room")
                return alarms
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read the active alarms from Room", e)
            }
        }
        val snapshot = deviceStore.loadActiveAlarms()
        Log.i(TAG, "loadActiveAlarms: $snapshot from the device-protected snapshot")
        return snapshot
    }

    /** Mirrors the current schedule into device-protected storage. */
    private suspend fun refreshMirror() {
        val dao = currentDao() ?: return
        try {
            deviceStore.saveActiveAlarms(dao.getActiveAlarms())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update the device-protected alarm snapshot", e)
        }
    }

    /**
     * The Room DAO, or `null` while the device is locked (Room lives in
     * credential-encrypted storage and cannot be opened before the first
     * unlock).
     */
    private fun currentDao(): AlarmDao? {
        if (!isUserUnlocked()) {
            Log.w(TAG, "currentDao: the device is locked, Room is not available")
            return null
        }
        return try {
            daoProvider()
        } catch (e: Exception) {
            Log.w(TAG, "currentDao: failed to open the Room database", e)
            null
        }
    }

    override fun isAlarmRinging(): Boolean = deviceStore.isRinging()

    @Synchronized
    override fun setRingingAlarm(): Boolean {
        if (deviceStore.isRinging()) return false
        deviceStore.setRinging(true)
        return true
    }

    override fun clearRingingAlarm() {
        deviceStore.setRinging(false)
    }

    override fun saveRingingAlarmState(state: RingingAlarmState) {
        stateStore.save(state)
    }

    override fun loadRingingAlarmState(): RingingAlarmState? {
        return stateStore.load()
    }

    override fun clearRingingAlarmState() {
        stateStore.clear()
    }

    override suspend fun deactivateOneShotAlarms(ids: List<Int>) {
        if (ids.isEmpty()) return
        val dao = currentDao()
        if (dao != null) {
            try {
                dao.deactivateOneShotAlarms(ids)
                refreshMirror()
                return
            } catch (e: Exception) {
                Log.w(TAG, "deactivateOneShotAlarms: Room is not writable, deferring $ids", e)
            }
        }
        // Locked: Room cannot be touched, so the deactivation is remembered in
        // device-protected storage and applied by syncAfterUnlock().
        Log.w(TAG, "deactivateOneShotAlarms: device locked, deferring $ids")
        deviceStore.addPendingDeactivations(ids)
        // Drop them from the mirrored snapshot as well, so a reboot before the
        // next unlock does not resurrect an alarm that already rang.
        val remaining = deviceStore.loadActiveAlarms().filterNot { it.id in ids }
        deviceStore.saveActiveAlarms(remaining)
    }

    override suspend fun cancelV1Alarms() {
        val prefs = credentialPrefs() ?: return
        if (prefs.getBoolean(KEY_V1_MIGRATED, false)) return
        val dao = currentDao() ?: return
        for (id in dao.getAllAlarmIds()) {
            val intent = Intent(context, AlarmReceiver::class.java)
            val pendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    id,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
            pendingIntent?.cancel()
        }
        prefs.edit { putBoolean(KEY_V1_MIGRATED, true) }
        scheduleNextAlarm()
    }

    override fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    /**
     * The credential-encrypted preferences, only readable once the user has
     * unlocked the device. `null` while locked.
     */
    private fun credentialPrefs() =
        if (!isUserUnlocked()) {
            null
        } else {
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            } catch (e: Exception) {
                Log.w(TAG, "credentialPrefs: the preferences are not available", e)
                null
            }
        }

    private fun cancelNextAlarm() {
        cancelAlarmSlot(NEXT_ALARM_REQUEST_CODE)
    }

    private fun cancelMissedAlarm() {
        cancelAlarmSlot(MISSED_ALARM_REQUEST_CODE)
    }

    private fun cancelAlarmSlot(requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        // FLAG_NO_CREATE: cancelling a slot must not create a PendingIntent
        // that was never registered, otherwise the slot would stay alive.
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
        pendingIntent?.let { alarmManager.cancel(it) }
    }
}
