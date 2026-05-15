package com.example.test.service

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { INFO, WARN, ERROR }

data class LogEntry(val text: String, val level: LogLevel = LogLevel.INFO)

/**
 * 进程级单例：HTTP 服务的运行状态与日志缓冲。
 *
 * 设计目的：UI 在 Compose 重组、Tab 切换、Activity 重建时不会丢失状态，
 * 因为状态生命周期挂在进程上，而不是某个 Composable 的 remember 上。
 */
object ServerStateHolder {

    private const val MAX_LOG_ENTRIES = 100

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    val logs = mutableStateListOf<LogEntry>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun addLog(message: String, level: LogLevel = LogLevel.INFO) {
        val stamped = "[${timeFormat.format(Date())}] $message"
        logs.add(0, LogEntry(stamped, level))
        while (logs.size > MAX_LOG_ENTRIES) {
            logs.removeAt(logs.lastIndex)
        }
    }

    fun addLog(message: String, levelStr: String) {
        val level = when (levelStr) {
            "WARN" -> LogLevel.WARN
            "ERROR" -> LogLevel.ERROR
            else -> LogLevel.INFO
        }
        addLog(message, level)
    }

    fun clearLogs() {
        logs.clear()
    }
}
