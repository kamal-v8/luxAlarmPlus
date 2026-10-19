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
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.MediumTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The device-protected store holds the schedule mirror and the ringing state
 * that has to be readable before the user unlocks the device.
 */
@MediumTest
class DeviceProtectedAlarmStoreTest {
    private lateinit var context: Context
    private lateinit var store: DeviceProtectedAlarmStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        store = DeviceProtectedAlarmStore(context)
        clearStore()
    }

    @After
    fun tearDown() {
        clearStore()
    }

    private fun clearStore() {
        store.saveActiveAlarms(emptyList())
        store.setRinging(false)
        store.clearRingingState()
        store.consumePendingDeactivations()
    }

    @Test
    fun saveAndLoadActiveAlarms_roundTripsEveryField() {
        val alarms =
            listOf(
                AlarmItem(
                    id = 1,
                    hour = 6,
                    minute = 30,
                    isActive = true,
                    repeatDays = setOf(2, 4, 6),
                    ringtoneUri = "content://media/internal/audio/media/42",
                    volume = 0.75f,
                    vibrationEnabled = true,
                ),
                AlarmItem(
                    id = 2,
                    hour = 22,
                    minute = 5,
                    isActive = true,
                    repeatDays = emptySet(),
                    ringtoneUri = null,
                    volume = null,
                    vibrationEnabled = false,
                ),
            )

        store.saveActiveAlarms(alarms)

        assertEquals(alarms, store.loadActiveAlarms())
    }

    @Test
    fun loadActiveAlarms_withoutSnapshot_isEmpty() {
        assertTrue(store.loadActiveAlarms().isEmpty())
    }

    @Test
    fun saveActiveAlarms_withEmptyList_clearsTheSnapshot() {
        store.saveActiveAlarms(listOf(AlarmItem(id = 1, hour = 6, minute = 30)))

        store.saveActiveAlarms(emptyList())

        assertTrue(store.loadActiveAlarms().isEmpty())
    }

    @Test
    fun loadActiveAlarms_withCorruptedSnapshot_isEmptyAndDoesNotThrow() {
        val prefs =
            context
                .createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit(commit = true) { putString(KEY_ACTIVE_ALARMS, "this is not json") }

        assertTrue(store.loadActiveAlarms().isEmpty())
    }

    @Test
    fun ringingFlag_roundTrips() {
        assertFalse(store.isRinging())

        store.setRinging(true)
        assertTrue(store.isRinging())

        store.setRinging(false)
        assertFalse(store.isRinging())
    }

    @Test
    fun saveAndLoadRingingState_roundTripsFullState() {
        val state =
            RingingAlarmState(
                alarmId = 7,
                ringtoneUri = "content://media/internal/audio/media/42",
                volume = 0.75f,
                vibrationEnabled = true,
            )

        store.saveRingingState(state)

        val loaded = store.loadRingingState()
        assertNotNull(loaded)
        assertEquals(state.alarmId, loaded.alarmId)
        assertEquals(state.ringtoneUri, loaded.ringtoneUri)
        assertEquals(state.volume, loaded.volume)
        assertEquals(state.vibrationEnabled, loaded.vibrationEnabled)
    }

    @Test
    fun saveAndLoadRingingState_withNullValues() {
        val state =
            RingingAlarmState(
                alarmId = 3,
                ringtoneUri = null,
                volume = null,
                vibrationEnabled = false,
            )

        store.saveRingingState(state)

        val loaded = store.loadRingingState()
        assertNotNull(loaded)
        assertEquals(3, loaded.alarmId)
        assertNull(loaded.ringtoneUri)
        assertNull(loaded.volume)
        assertEquals(false, loaded.vibrationEnabled)
    }

    @Test
    fun loadRingingState_withoutState_isNull() {
        assertNull(store.loadRingingState())
    }

    @Test
    fun clearRingingState_removesTheState() {
        store.saveRingingState(RingingAlarmState(1, "x", 0.5f, true))

        store.clearRingingState()

        assertNull(store.loadRingingState())
    }

    @Test
    fun pendingDeactivations_areKeptUntilConsumed() {
        store.addPendingDeactivations(listOf(4, 5))

        assertEquals(listOf(4, 5), store.consumePendingDeactivations())
        assertTrue(store.consumePendingDeactivations().isEmpty())
    }

    @Test
    fun pendingDeactivations_areMerged() {
        store.addPendingDeactivations(listOf(4))
        store.addPendingDeactivations(listOf(5, 6))

        assertEquals(listOf(4, 5, 6), store.consumePendingDeactivations().sorted())
    }

    @Test
    fun pendingDeactivations_withEmptyList_staysEmpty() {
        store.addPendingDeactivations(emptyList())

        assertTrue(store.consumePendingDeactivations().isEmpty())
    }

    private companion object {
        // Mirrors the private constants of DeviceProtectedAlarmStore, they are
        // needed to write a corrupted snapshot.
        const val PREFS_NAME = "device_protected_alarms"
        const val KEY_ACTIVE_ALARMS = "active_alarms"
    }
}
