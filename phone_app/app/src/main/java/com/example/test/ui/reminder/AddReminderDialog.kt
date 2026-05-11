package com.example.test.ui.reminder

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.test.model.MedicineReminder
import java.util.Calendar

/**
 * 新建 / 编辑服药提醒对话框。
 * 支持多个提醒时间，通过 TimeChipRow 管理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddReminderDialog(
    initialReminder: MedicineReminder? = null,
    onDismiss: () -> Unit,
    onConfirm: (MedicineReminder) -> Unit
) {
    val context = LocalContext.current

    // ── 表单字段 ──
    var name by remember { mutableStateOf(initialReminder?.name ?: "") }
    var timesPerDay by remember { mutableStateOf(initialReminder?.timesPerDay ?: "3") }
    var amount by remember { mutableStateOf(initialReminder?.amount ?: "1") }
    var selectedUnit by remember { mutableStateOf(initialReminder?.unit ?: "粒") }
    val units = listOf("粒", "毫升", "支", "片")

    var nameError by remember { mutableStateOf(false) }
    var amountError by remember { mutableStateOf(false) }

    // ── 多时间状态 ──
    val timesList = remember { mutableStateListOf<String>() }
    // 当前正在独立编辑的卡片索引；-1 表示未在编辑
    var editingIndex by remember { mutableStateOf(-1) }

    // 初始化：从已有提醒加载时间列表
    LaunchedEffect(initialReminder) {
        if (timesList.isEmpty()) {
            initialReminder?.times?.let { timesList.addAll(it.sorted()) }
        }
    }

    val defaultHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val defaultMinute = Calendar.getInstance().get(Calendar.MINUTE)

    // 主 TimePicker 状态：仅作为"新增输入"，不与任何卡片做双向绑定
    val mainTpState = rememberTimePickerState(
        initialHour = defaultHour,
        initialMinute = defaultMinute,
        is24Hour = true
    )

    // ── 重复周期 ──
    val selectedDays = remember {
        mutableStateOf(initialReminder?.repeatDays ?: setOf(1, 2, 3, 4, 5, 6, 7))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialReminder == null) "新建服药提醒" else "编辑服药提醒")
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 药品名称
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; if (it.isNotBlank()) nameError = false },
                        label = { Text("药品名称") },
                        isError = nameError,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                // 次数 + 用量
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedTextField(
                            value = timesPerDay,
                            onValueChange = { timesPerDay = it },
                            label = { Text("次数/天") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { amount = it; if (it.isNotBlank()) amountError = false },
                            label = { Text("用量/次") },
                            isError = amountError,
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
                // 单位
                item {
                    Text("单位", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        units.forEach { unit ->
                            FilterChip(
                                selected = (selectedUnit == unit),
                                onClick = { selectedUnit = unit },
                                label = { Text(unit, fontSize = 12.sp) }
                            )
                        }
                    }
                }
                // ── 提醒时间（多时间卡片 + TimePicker） ──
                item {
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("提醒时间", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(4.dp))

                    // 时间卡片行：点击卡片 → 打开独立编辑对话框；[+] 从主 TimePicker 读取时间
                    TimeChipRow(
                        times = timesList.toList(),
                        selectedIndex = -1,
                        onSelect = { index -> editingIndex = index },
                        onDelete = { index -> timesList.removeAt(index) },
                        onAdd = {
                            val newTime = String.format(
                                "%02d:%02d", mainTpState.hour, mainTpState.minute
                            )
                            if (timesList.contains(newTime)) {
                                Toast.makeText(context, "该时间已存在", Toast.LENGTH_SHORT).show()
                            } else {
                                timesList.add(newTime)
                                val sorted = timesList.sorted()
                                timesList.clear()
                                timesList.addAll(sorted)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        TimePicker(state = mainTpState)
                    }
                }
                // 重复周期
                item {
                    Text("重复周期", style = MaterialTheme.typography.labelSmall)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            (1..4).forEach { day ->
                                FilterChip(
                                    selected = selectedDays.value.contains(day),
                                    onClick = {
                                        val cur = selectedDays.value.toMutableSet()
                                        if (cur.contains(day)) cur.remove(day) else cur.add(day)
                                        selectedDays.value = cur
                                    },
                                    label = {
                                        Text(
                                            when (day) {
                                                1 -> "一"; 2 -> "二"; 3 -> "三"; else -> "四"
                                            },
                                            fontSize = 12.sp
                                        )
                                    }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            (5..7).forEach { day ->
                                FilterChip(
                                    selected = selectedDays.value.contains(day),
                                    onClick = {
                                        val cur = selectedDays.value.toMutableSet()
                                        if (cur.contains(day)) cur.remove(day) else cur.add(day)
                                        selectedDays.value = cur
                                    },
                                    label = {
                                        Text(
                                            when (day) {
                                                5 -> "五"; 6 -> "六"; else -> "日"
                                            },
                                            fontSize = 12.sp
                                        )
                                    },
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    name.isBlank() || amount.isBlank() -> {
                        nameError = name.isBlank()
                        amountError = amount.isBlank()
                        Toast.makeText(context, "请完善药品信息", Toast.LENGTH_SHORT).show()
                    }
                    timesList.isEmpty() -> {
                        Toast.makeText(context, "请至少添加一个提醒时间", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        onConfirm(
                            MedicineReminder(
                                id = initialReminder?.id ?: System.currentTimeMillis(),
                                name = name,
                                timesPerDay = timesPerDay,
                                amount = amount,
                                unit = selectedUnit,
                                times = timesList.sorted(),
                                repeatDays = selectedDays.value,
                                isActive = initialReminder?.isActive ?: true
                            )
                        )
                    }
                }
            }) { Text(if (initialReminder == null) "添加" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    // 独立编辑对话框：点击某张时间卡片后打开，保存时回写到对应索引
    if (editingIndex in timesList.indices) {
        EditTimeDialog(
            initialTime = timesList[editingIndex],
            onDismiss = { editingIndex = -1 },
            onConfirm = { newTime ->
                val idx = editingIndex
                if (idx in timesList.indices) {
                    if (newTime != timesList[idx] && timesList.contains(newTime)) {
                        Toast.makeText(context, "该时间已存在", Toast.LENGTH_SHORT).show()
                    } else {
                        timesList[idx] = newTime
                        val sorted = timesList.sorted()
                        timesList.clear()
                        timesList.addAll(sorted)
                    }
                }
                editingIndex = -1
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTimeDialog(
    initialTime: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val parts = initialTime.split(":")
    val initH = parts.getOrNull(0)?.toIntOrNull() ?: 8
    val initM = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val tpState = rememberTimePickerState(
        initialHour = initH,
        initialMinute = initM,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑提醒时间") },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TimePicker(state = tpState)
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(String.format("%02d:%02d", tpState.hour, tpState.minute))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
