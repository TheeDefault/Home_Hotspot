package com.homehotspot

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogStatus {
    INFO,
    SUCCESS,
    FAILED,
    UNAVAILABLE
}

data class LogEntry(
    val timestamp: String = getCurrentTimestamp(),
    val action: String,
    val status: LogStatus,
    val details: String
) {
    companion object {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        fun getCurrentTimestamp(): String {
            return synchronized(dateFormat) {
                dateFormat.format(Date())
            }
        }

        fun fromJson(jsonStr: String): LogEntry? {
            return try {
                val obj = JSONObject(jsonStr)
                LogEntry(
                    timestamp = obj.optString("timestamp", getCurrentTimestamp()),
                    action = obj.optString("action", "UNKNOWN"),
                    status = try {
                        LogStatus.valueOf(obj.optString("status", LogStatus.INFO.name))
                    } catch (e: Exception) {
                        LogStatus.INFO
                    },
                    details = obj.optString("details", "")
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("timestamp", timestamp)
        obj.put("action", action)
        obj.put("status", status.name)
        obj.put("details", details)
        return obj.toString()
    }
}
