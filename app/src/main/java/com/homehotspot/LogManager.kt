package com.homehotspot

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import java.util.concurrent.CopyOnWriteArrayList

object LogManager {
    private const val TAG = "HomeHotspotLog"
    private const val PREFS_NAME = "home_hotspot_logs"
    private const val KEY_LOGS = "persisted_logs"
    private const val MAX_LOG_ENTRIES = 500

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(List<LogEntry>) -> Unit>()
    private val inMemoryLogs = ArrayList<LogEntry>()
    private var isLoaded = false
    private var appContext: Context? = null
    private val lock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
        loadLogs()
    }

    private fun getPrefs(): SharedPreferences? {
        return appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun loadLogs() {
        synchronized(lock) {
            if (isLoaded) return
            try {
                val prefs = getPrefs() ?: return
                val rawJson = prefs.getString(KEY_LOGS, null)
                inMemoryLogs.clear()
                if (!rawJson.isNullOrEmpty()) {
                    val array = JSONArray(rawJson)
                    for (i in 0 until array.length()) {
                        val itemStr = array.optString(i)
                        val entry = LogEntry.fromJson(itemStr)
                        if (entry != null) {
                            inMemoryLogs.add(entry)
                        }
                    }
                }
                isLoaded = true
            } catch (e: Exception) {
                Log.e(TAG, "Error loading logs", e)
            }
        }
    }

    fun log(action: String, status: LogStatus, details: String) {
        try {
            val entry = LogEntry(
                action = action,
                status = status,
                details = details
            )
            Log.d(TAG, "[${entry.timestamp}] [${entry.status}] ${entry.action}: ${entry.details}")

            val currentList = synchronized(lock) {
                if (!isLoaded) {
                    loadLogs()
                }
                inMemoryLogs.add(0, entry) // newest first
                if (inMemoryLogs.size > MAX_LOG_ENTRIES) {
                    inMemoryLogs.removeAt(inMemoryLogs.size - 1)
                }
                saveLogsLocked()
                ArrayList(inMemoryLogs)
            }

            notifyListeners(currentList)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record log entry safely", e)
        }
    }

    private fun saveLogsLocked() {
        try {
            val prefs = getPrefs() ?: return
            val array = JSONArray()
            for (entry in inMemoryLogs) {
                array.put(entry.toJson())
            }
            prefs.edit().putString(KEY_LOGS, array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save logs to preferences", e)
        }
    }

    fun getLogs(): List<LogEntry> {
        return synchronized(lock) {
            if (!isLoaded) {
                loadLogs()
            }
            ArrayList(inMemoryLogs)
        }
    }

    fun clearLogs() {
        try {
            synchronized(lock) {
                inMemoryLogs.clear()
                getPrefs()?.edit()?.remove(KEY_LOGS)?.apply()
            }
            notifyListeners(emptyList())
            log("LOGS_CLEARED", LogStatus.INFO, "Activity logs cleared by user")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }

    fun addListener(listener: (List<LogEntry>) -> Unit) {
        listeners.add(listener)
        // Emit current logs immediately
        val current = getLogs()
        mainHandler.post { listener(current) }
    }

    fun removeListener(listener: (List<LogEntry>) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(logs: List<LogEntry>) {
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener(logs)
                } catch (e: Exception) {
                    Log.e(TAG, "Listener error", e)
                }
            }
        }
    }
}
