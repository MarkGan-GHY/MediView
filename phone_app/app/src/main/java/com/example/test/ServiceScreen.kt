package com.example.test

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.test.service.HttpServerService
import com.example.test.service.LogLevel
import com.example.test.service.ServerStateHolder
import android.content.Intent
import java.net.NetworkInterface

@Composable
fun ServiceScreen() {
    val context = LocalContext.current

    val isServerRunning by ServerStateHolder.isRunning.collectAsState()
    val logMessages = ServerStateHolder.logs
    var localIp by remember { mutableStateOf("获取中...") }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        localIp = getLocalIpAddress()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusCard(
            isRunning = isServerRunning,
            localIp = localIp,
            port = HttpServerService.SERVER_PORT
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { startHttpServer(context) },
                enabled = !isServerRunning,
                modifier = Modifier.weight(1f)
            ) {
                Text("启动服务")
            }
            Button(
                onClick = { stopHttpServer(context) },
                enabled = isServerRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("停止服务")
            }
        }

        if (isServerRunning) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("眼镜端接口地址", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "POST http://$localIp:${HttpServerService.SERVER_PORT}/analyzeDrug",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Text("请求日志", style = MaterialTheme.typography.titleSmall)
        if (logMessages.isEmpty()) {
            Text(
                "等待眼镜端连接...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(logMessages) { entry ->
                    val color = when (entry.level) {
                        LogLevel.WARN  -> Color(0xFFF57F17)  // amber
                        LogLevel.ERROR -> MaterialTheme.colorScheme.error
                        LogLevel.INFO  -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        entry.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = color
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(isRunning: Boolean, localIp: String, port: Int) {
    val statusText = if (isRunning) "运行中" else "已停止"
    val statusColor = if (isRunning) Color(0xFF2E7D32) else Color(0xFFC62828)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("服务状态", style = MaterialTheme.typography.titleMedium)
                Text(statusText, color = statusColor, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("本机 IP：$localIp", style = MaterialTheme.typography.bodyMedium)
            Text("监听端口：$port", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun getLocalIpAddress(): String {
    return try {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.firstOrNull { addr ->
                !addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false
            }
            ?.hostAddress ?: "无法获取"
    } catch (e: Exception) {
        "无法获取"
    }
}

private fun startHttpServer(context: Context) {
    val intent = Intent(context, HttpServerService::class.java).apply {
        action = HttpServerService.ACTION_START
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun stopHttpServer(context: Context) {
    context.startService(
        Intent(context, HttpServerService::class.java).apply {
            action = HttpServerService.ACTION_STOP
        }
    )
}
