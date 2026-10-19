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

/**
 * Applies the work that was deferred while the device was locked.
 *
 * The receiver is direct-boot aware, so it is delivered as soon as the user
 * unlocks the device. The repository then deactivates the one-shot alarms
 * that fired while locked, re-syncs the device-protected snapshot of the
 * schedule and re-schedules the next alarm.
 */
class UserUnlockedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_USER_UNLOCKED) return

        Log.i(TAG, "User unlocked the device: applying the deferred alarm work")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppContainer.repository.syncAfterUnlock()
                // The settings may still live in credential-encrypted storage
                // if the app was started while the device was locked.
                AppContainer.settingsManager.migrateLegacyPreferences()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync after unlock", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "UserUnlockedReceiver"
    }
}
