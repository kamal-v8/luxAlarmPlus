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

import android.app.Application
import android.content.Context
import android.util.Log
import com.dsalmun.luxalarmplus.data.AlarmDatabase
import com.dsalmun.luxalarmplus.data.AlarmRepository
import com.dsalmun.luxalarmplus.data.IAlarmRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppContainer : Application() {
    companion object {
        private const val TAG = "AppContainer"

        private lateinit var appContext: Context

        lateinit var repository: IAlarmRepository
        lateinit var settingsManager: SettingsManager

        private var databaseInstance: AlarmDatabase? = null

        /**
         * The Room database lives in credential-encrypted storage, so it can
         * only be opened once the user has unlocked the device. It is
         * therefore created on first access instead of in [onCreate], which
         * also runs in the locked (direct boot) state.
         */
        val database: AlarmDatabase
            get() =
                databaseInstance
                    ?: synchronized(this) {
                        databaseInstance
                            ?: AlarmDatabase.getDatabase(appContext).also { databaseInstance = it }
                    }
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        settingsManager = SettingsManager(this)
        // The DAO is resolved lazily: the repository falls back to the
        // device-protected snapshot while the device is locked.
        repository = AlarmRepository(this, daoProvider = { database.alarmDao() })

        if (repository.isUserUnlocked()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    repository.cancelV1Alarms()
                } catch (e: Exception) {
                    Log.w(TAG, "cancelV1Alarms failed", e)
                }
            }
        } else {
            Log.i(TAG, "onCreate: device is locked, running in direct boot mode")
        }
    }
}
