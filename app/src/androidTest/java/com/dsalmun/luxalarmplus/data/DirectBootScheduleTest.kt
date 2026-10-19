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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.MediumTest
import com.dsalmun.luxalarmplus.AlarmReceiver
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The locked (direct boot) path has to produce exactly the same schedule as
 * the unlocked one, so an alarm still rings when the phone is powered off
 * and never unlocked.
 */
@MediumTest
class DirectBootScheduleTest {
    /** The request code the repository uses for the next alarm slot. */
    private companion object {
        const val NEXT_ALARM_REQUEST_CODE = 0
    }

    private lateinit var context: Context
    private lateinit var store: DeviceProtectedAlarmStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        store = DeviceProtectedAlarmStore(context)
        store.saveActiveAlarms(emptyList())
        store.consumePendingDeactivations()
    }

    @After
    fun tearDown() {
        store.saveActiveAlarms(emptyList())
        store.consumePendingDeactivations()
    }

    private fun testAlarms(): List<AlarmItem> {
        val today = Calendar.getInstance()[Calendar.DAY_OF_WEEK]
        return listOf(
            AlarmItem(id = 1, hour = 7, minute = 0),
            AlarmItem(id = 2, hour = 7, minute = 0, repeatDays = setOf(today)),
            AlarmItem(id = 3, hour = 23, minute = 59, repeatDays = setOf(today)),
            AlarmItem(id = 4, hour = 6, minute = 15, ringtoneUri = "content://media/1", volume = 0.5f),
        )
    }

    @Test
    fun lockedSchedule_matchesUnlockedSchedule() {
        runBlocking {
            val alarms = testAlarms()
            val database =
                Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            try {
                alarms.forEach { database.alarmDao().insert(it) }
                val fromDao = database.alarmDao().getActiveAlarms()

                store.saveActiveAlarms(alarms)
                val fromSnapshot = store.loadActiveAlarms()

                assertEquals(fromDao, fromSnapshot)

                val now = System.currentTimeMillis()
                val daoSlot = AlarmScheduleCalculator.nextTriggerSlot(fromDao, now)
                val snapshotSlot = AlarmScheduleCalculator.nextTriggerSlot(fromSnapshot, now)

                assertEquals(daoSlot.triggerAtMillis, snapshotSlot.triggerAtMillis)
                assertEquals(daoSlot.alarms, snapshotSlot.alarms)
                assertEquals(
                    AlarmScheduleCalculator.findMissedAlarms(fromDao, now),
                    AlarmScheduleCalculator.findMissedAlarms(fromSnapshot, now),
                )
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun lockedRepository_schedulesFromTheSnapshotWithoutTouchingRoom() {
        runBlocking {
            val dao = RecordingAlarmDao()
            store.saveActiveAlarms(listOf(AlarmItem(id = 1, hour = 7, minute = 0)))
            val repository =
                AlarmRepository(context, daoProvider = { dao }, unlockedProvider = { false })

            assertFalse(repository.isUserUnlocked())
            assertTrue(repository.rescheduleAfterBoot())
            assertEquals(0, dao.callCount)

            // The alarm was really registered with the AlarmManager, the
            // PendingIntent of the next alarm slot exists.
            val nextAlarmSlot =
                PendingIntent.getBroadcast(
                    context,
                    NEXT_ALARM_REQUEST_CODE,
                    Intent(context, AlarmReceiver::class.java),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
            assertNotNull(nextAlarmSlot, "the next alarm should be scheduled from the snapshot")

            // Clean up the alarm that was really registered with the AlarmManager.
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(nextAlarmSlot)
            store.saveActiveAlarms(emptyList())
            repository.scheduleNextAlarm()
        }
    }

    @Test
    fun lockedRepository_defersOneShotDeactivation() {
        runBlocking {
            val dao = RecordingAlarmDao()
            val repository =
                AlarmRepository(context, daoProvider = { dao }, unlockedProvider = { false })

            repository.deactivateOneShotAlarms(listOf(3, 4))

            assertEquals(0, dao.callCount)
            assertEquals(listOf(3, 4), store.consumePendingDeactivations().sorted())
        }
    }

    @Test
    fun unlockedRepository_appliesThePendingDeactivations() {
        runBlocking {
            val dao = RecordingAlarmDao()
            store.addPendingDeactivations(listOf(7))
            val repository =
                AlarmRepository(context, daoProvider = { dao }, unlockedProvider = { true })

            repository.syncAfterUnlock()

            assertEquals(listOf(7), dao.deactivatedIds)
            assertTrue(store.consumePendingDeactivations().isEmpty())
        }
    }

    /**
     * An alarm that rang while the device was locked has a pending
     * deactivation. The deactivation has to be applied before the missed
     * alarms are looked at, otherwise the alarm that just rang is treated as
     * missed and rings a second time.
     */
    @Test
    fun unlockedRepository_appliesThePendingDeactivationBeforeTheMissedAlarmRetry() {
        runBlocking {
            val past = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, -1) }
            val alarm = AlarmItem(id = 7, hour = past[Calendar.HOUR_OF_DAY], minute = past[Calendar.MINUTE])
            val dao = RecordingAlarmDao(activeAlarms = listOf(alarm))
            store.addPendingDeactivations(listOf(7))
            val repository =
                AlarmRepository(context, daoProvider = { dao }, unlockedProvider = { true })

            repository.rescheduleAfterBoot()

            assertEquals(listOf(7), dao.deactivatedIds)
            assertTrue(
                dao.callLog.indexOf("deactivateOneShotAlarms") <
                    dao.callLog.indexOf("getActiveAlarms"),
            )
            // The alarm is inactive now, so it is not treated as missed.
            assertTrue(dao.activeAlarms.isEmpty())

            // Clean up the alarms that were really registered.
            repository.scheduleNextAlarm()
        }
    }

    /** A DAO that records the calls, standing in for an unavailable Room. */
    private class RecordingAlarmDao(
        var activeAlarms: List<AlarmItem> = emptyList(),
    ) : AlarmDao {
        var callCount = 0
        val deactivatedIds = mutableListOf<Int>()
        val callLog = mutableListOf<String>()

        override suspend fun insert(alarm: AlarmItem): Long {
            callCount++
            callLog += "insert"
            return alarm.id.toLong()
        }

        override suspend fun update(alarm: AlarmItem) {
            callCount++
            callLog += "update"
        }

        override suspend fun delete(alarm: AlarmItem) {
            callCount++
            callLog += "delete"
        }

        override fun getAllAlarms(): Flow<List<AlarmItem>> {
            callCount++
            callLog += "getAllAlarms"
            return flowOf(emptyList())
        }

        override suspend fun getAlarmById(id: Int): AlarmItem? {
            callCount++
            callLog += "getAlarmById"
            return null
        }

        override suspend fun getActiveAlarms(): List<AlarmItem> {
            callCount++
            callLog += "getActiveAlarms"
            return activeAlarms
        }

        override suspend fun getAllAlarmIds(): List<Int> {
            callCount++
            callLog += "getAllAlarmIds"
            return emptyList()
        }

        override suspend fun deactivateOneShotAlarms(ids: List<Int>) {
            callCount++
            callLog += "deactivateOneShotAlarms"
            deactivatedIds.addAll(ids)
            activeAlarms = activeAlarms.filterNot { it.id in ids }
        }
    }
}
