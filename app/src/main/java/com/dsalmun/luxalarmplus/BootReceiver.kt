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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val isBoot =
            action == Intent.ACTION_BOOT_COMPLETED ||
                // Delivered to direct-boot-aware components while the device is
                // still encrypted. BOOT_COMPLETED only arrives after the first
                // unlock, so this is the broadcast that makes the alarm survive
                // a reboot on a device that is never unlocked.
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == QUICKBOOT_POWERON
        val isReschedule =
            isBoot ||
                action == Intent.ACTION_TIME_CHANGED ||
                action == Intent.ACTION_TIMEZONE_CHANGED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (!isReschedule) return

        // Cancel any leftover watchdog from before reboot
        try {
            AlarmWatchdogReceiver.cancelWatchdog(context)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel watchdog on $action", e)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val locked = !AppContainer.repository.isUserUnlocked()
                AppContainer.repository.clearRingingAlarm()
                AppContainer.repository.clearRingingAlarmState()
                if (isBoot) {
                    if (locked) {
                        Log.i(
                            TAG,
                            "Boot detected ($action): rescheduling from device-protected snapshot (locked)",
                        )
                    } else {
                        Log.i(TAG, "Boot detected ($action): rescheduling with missed-alarm retry")
                    }
                    AppContainer.repository.rescheduleAfterBoot()
                } else {
                    Log.i(TAG, "System event ($action): re-scheduling next alarm")
                    AppContainer.repository.scheduleNextAlarm()
                }
            } catch (e: Exception) {
                // Direct-boot locked state: DB/prefs may be unavailable. Failure
                // here just means the next USER_UNLOCKED or BOOT_COMPLETED
                // event retries.
                Log.w(TAG, "Reschedule failed on $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
