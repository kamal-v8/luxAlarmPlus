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
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.dsalmun.luxalarmplus.data.AlarmItem
import com.dsalmun.luxalarmplus.data.AlarmScheduleCalculator
import java.util.Calendar
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Seeds exactly one real alarm into the app database and refreshes the
 * device-protected mirror, so the locked (direct boot) path can be verified
 * on the emulator.
 *
 * The alarm is scheduled [minutesAhead] minutes in the future, rounded up to
 * the next 5-minute step - the same granularity the Material3 time picker
 * offers. The argument is passed with
 * `-e minutesAhead <n>` to the instrumentation runner.
 *
 * The result is logged with the tag [TAG] so that the end-to-end harness can
 * read it back from the logcat.
 */
@MediumTest
class SeedAlarmTest {
    @Test
    fun seed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val arguments = InstrumentationRegistry.getArguments()
        val minutesAhead =
            (arguments.getString("minutesAhead") ?: DEFAULT_MINUTES_AHEAD).toInt().coerceAtLeast(1)

        val now = System.currentTimeMillis()
        // Same rounding as the Material3 time picker: full 5-minute steps.
        val targetMillis = ((now + minutesAhead * 60_000L + STEP_MS - 1) / STEP_MS) * STEP_MS
        val target = Calendar.getInstance().apply { timeInMillis = targetMillis }

        runBlocking {
            val dao = AppContainer.database.alarmDao()
            // Start from a clean slate: exactly one alarm.
            for (id in dao.getAllAlarmIds()) {
                dao.getAlarmById(id)?.let { dao.delete(it) }
            }

            val alarm =
                AlarmItem(
                    hour = target[Calendar.HOUR_OF_DAY],
                    minute = target[Calendar.MINUTE],
                    isActive = true,
                )
            val id = dao.insert(alarm)
            log("inserted id=$id alarm=$alarm")

            // Registers the alarm with the AlarmManager and refreshes the
            // device-protected mirror of the schedule.
            val scheduled = AppContainer.repository.scheduleNextAlarm()
            log("scheduleNextAlarm=$scheduled")

            val active = dao.getActiveAlarms()
            log("active=$active")
            assertTrue(scheduled, "the alarm could not be scheduled")
            assertTrue(active.isNotEmpty(), "no active alarm after the seeding")

            val next = AlarmScheduleCalculator.nextTriggerSlot(active, now)
            log("nextTrigger=${next.triggerAtMillis} alarms=${next.alarms}")
        }
    }

    private fun log(message: String) {
        Log.i(TAG, "SEED $message")
        println("SEED $message")
    }

    private companion object {
        const val TAG = "SeedAlarmTest"
        const val DEFAULT_MINUTES_AHEAD = "10"
        const val STEP_MS = 5 * 60_000L
    }
}
