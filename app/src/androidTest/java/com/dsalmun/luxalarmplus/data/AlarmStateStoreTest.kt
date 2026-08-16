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

import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.MediumTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test

@MediumTest
class AlarmStateStoreTest {
    private lateinit var store: AlarmStateStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        store = AlarmStateStore(context)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun saveAndLoad_roundTripsFullState() {
        val state = RingingAlarmState(
            alarmId = 7,
            ringtoneUri = "content://media/internal/audio/media/42",
            volume = 0.75f,
            vibrationEnabled = true,
        )
        store.save(state)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(state.alarmId, loaded.alarmId)
        assertEquals(state.ringtoneUri, loaded.ringtoneUri)
        assertEquals(state.volume, loaded.volume)
        assertEquals(state.vibrationEnabled, loaded.vibrationEnabled)
    }

    @Test
    fun saveAndLoad_nullRingtoneUri() {
        val state = RingingAlarmState(
            alarmId = 3,
            ringtoneUri = null,
            volume = null,
            vibrationEnabled = false,
        )
        store.save(state)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(3, loaded.alarmId)
        assertNull(loaded.ringtoneUri)
        assertNull(loaded.volume)
        assertEquals(false, loaded.vibrationEnabled)
    }

    @Test
    fun load_returnsNullWhenEmpty() {
        assertNull(store.load())
    }

    @Test
    fun clear_removesState() {
        store.save(RingingAlarmState(alarmId = 1, ringtoneUri = "x", volume = 0.5f, vibrationEnabled = true))
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun save_overwritesPreviousState() {
        store.save(RingingAlarmState(alarmId = 1, ringtoneUri = "first", volume = 0.1f, vibrationEnabled = true))
        store.save(RingingAlarmState(alarmId = 2, ringtoneUri = "second", volume = null, vibrationEnabled = false))

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(2, loaded.alarmId)
        assertEquals("second", loaded.ringtoneUri)
        assertNull(loaded.volume)
        assertEquals(false, loaded.vibrationEnabled)
    }
}
