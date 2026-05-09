package com.example.test

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar

// 1. 数据模型
data class MedicineReminder(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val timesPerDay: String,
    val amount: String,
    val unit: String,
    val time: String,
    val repeatDays: Set<Int>,
    val isActive: Boolean = true
)

// 2. ViewModel
class ReminderViewModel(context: Context) : ViewModel() {
    private val sharedPreferences = context.getSharedPreferences("medicine_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    val reminders = mutableStateListOf<MedicineReminder>()

    init { loadFromDisk() }

    private fun loadFromDisk() {
        val json = sharedPreferences.getString("reminders_list", null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<MedicineReminder>>() {}.type
                val savedList: List<MedicineReminder> = gson.fromJson(json, type)
                reminders.clear()
                reminders.addAll(savedList)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun saveToDisk() {
        val json = gson.toJson(reminders.toList())
        sharedPreferences.edit().putString("reminders_list", json).apply()
    }

    fun addReminder(reminder: MedicineReminder) {
        reminders.add(reminder)
        saveToDisk()
    }

    fun deleteReminder(reminder: MedicineReminder) {
        reminders.remove(reminder)
        saveToDisk()
    }

    fun updateReminder(updatedReminder: MedicineReminder) {
        val index = reminders.indexOfFirst { it.id == updatedReminder.id }
        if (index != -1) {
            reminders[index] = updatedReminder
            saveToDisk()
        }
    }

    fun updateReminderStatus(id: Long, isActive: Boolean) {
        val index = reminders.indexOfFirst { it.id == id }
        if (index != -1) {
            reminders[index] = reminders[index].copy(isActive = isActive)
            saveToDisk()
        }
    }
}

@Composable
fun ReminderScreen() {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var reminderToDelete by remember { mutableStateOf<MedicineReminder?>(null) }
    var reminderToEdit by remember { mutableStateOf<MedicineReminder?>(null) }
    var lastOperatedId by remember { mutableLongStateOf(-1L) }

    val listState = rememberLazyListState()
    val viewModel: ReminderViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return ReminderViewModel(context.applicationContext) as T
            }
        }
    )

    val sortedReminders by remember {
        derivedStateOf {
            viewModel.reminders.sortedWith(
                compareByDescending<MedicineReminder> { it.isActive }
                    .thenBy { it.time }
            )
        }
    }

    LaunchedEffect(sortedReminders) {
        if (lastOperatedId != -1L) {
            val targetIndex = sortedReminders.indexOfFirst { it.id == lastOperatedId }
            if (targetIndex != -1) {
                listState.animateScrollToItem(targetIndex)
                lastOperatedId = -1L
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "服药提醒清单", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            if (viewModel.reminders.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无提醒，请点击右下角添加", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 80.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = sortedReminders,
                        key = { _, item -> item.id }
                    ) { _, reminder ->
                        ReminderCard(
                            modifier = Modifier.animateItem(
                                placementSpec = spring(stiffness = 300f, dampingRatio = 0.8f)
                            ),
                            reminder = reminder,
                            onActiveChange = { newState ->
                                lastOperatedId = reminder.id
                                viewModel.updateReminderStatus(reminder.id, newState)
                            },
                            onDelete = { reminderToDelete = reminder },
                            onEdit = { reminderToEdit = reminder }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, contentDescription = "添加")
        }

        if (reminderToDelete != null) {
            AlertDialog(
                onDismissRequest = { reminderToDelete = null },
                title = { Text("确认删除") },
                text = { Text("确定要删除“${reminderToDelete?.name}”吗？") },
                confirmButton = {
                    TextButton(onClick = {
                        reminderToDelete?.let { viewModel.deleteReminder(it) }
                        reminderToDelete = null
                    }) { Text("删除", color = Color.Red) }
                },
                dismissButton = { TextButton(onClick = { reminderToDelete = null }) { Text("取消") } }
            )
        }

        if (showAddDialog) {
            AddReminderDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { newReminder ->
                    viewModel.addReminder(newReminder)
                    showAddDialog = false
                }
            )
        }

        if (reminderToEdit != null) {
            AddReminderDialog(
                initialReminder = reminderToEdit,
                onDismiss = { reminderToEdit = null },
                onConfirm = { updatedReminder ->
                    viewModel.updateReminder(updatedReminder)
                    reminderToEdit = null
                }
            )
        }
    }
}

@Composable
fun ReminderCard(
    modifier: Modifier = Modifier,
    reminder: MedicineReminder,
    onActiveChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (reminder.isActive) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, null, tint = if (reminder.isActive) MaterialTheme.colorScheme.primary else Color.Gray)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = reminder.time, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = if (reminder.isActive) Color.Unspecified else Color.Gray)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color.Red.copy(alpha = 0.6f))
                    }
                    Switch(checked = reminder.isActive, onCheckedChange = { onActiveChange(it) })
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = reminder.name, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = if (reminder.isActive) MaterialTheme.colorScheme.onSurface else Color.Gray)
            Text(text = "用法：一天 ${reminder.timesPerDay} 次，一次 ${reminder.amount} ${reminder.unit}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (reminder.isActive) MaterialTheme.colorScheme.secondary else Color.Gray)

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val weekDays = listOf("一", "二", "三", "四", "五", "六", "日")
                weekDays.forEachIndexed { index, day ->
                    val isSelected = reminder.repeatDays.contains(index + 1)
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (isSelected && reminder.isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color.LightGray)
                    ) {
                        Text(text = day, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp, color = if (isSelected && reminder.isActive) MaterialTheme.colorScheme.onPrimaryContainer else Color.Gray)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddReminderDialog(
    initialReminder: MedicineReminder? = null,
    onDismiss: () -> Unit,
    onConfirm: (MedicineReminder) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initialReminder?.name ?: "") }
    var timesPerDay by remember { mutableStateOf(initialReminder?.timesPerDay ?: "3") }
    var amount by remember { mutableStateOf(initialReminder?.amount ?: "1") }
    var selectedUnit by remember { mutableStateOf(initialReminder?.unit ?: "粒") }
    val units = listOf("粒", "毫升", "支", "片")

    var nameError by remember { mutableStateOf(false) }
    var amountError by remember { mutableStateOf(false) }

    val initialHour = initialReminder?.time?.split(":")?.get(0)?.toIntOrNull() ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val initialMinute = initialReminder?.time?.split(":")?.get(1)?.toIntOrNull() ?: Calendar.getInstance().get(Calendar.MINUTE)

    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )

    val selectedDays = remember { mutableStateOf(initialReminder?.repeatDays ?: setOf(1, 2, 3, 4, 5, 6, 7)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialReminder == null) "新建服药提醒" else "编辑服药提醒") },
        text = {
            // 使用特定的高度约束和较小的间距，确保内容可见
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedTextField(value = timesPerDay, onValueChange = { timesPerDay = it }, label = { Text("次数/天") }, modifier = Modifier.weight(1f), singleLine = true)
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
                item {
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("提醒时间", style = MaterialTheme.typography.labelSmall)
                    // 关键优化：包裹 Box 居中显示，TimePicker 会根据空间自适应
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TimePicker(state = timePickerState)
                    }
                }
                item {
                    Text("重复周期", style = MaterialTheme.typography.labelSmall)
                    // 增加横向滚动或流式布局，这里使用分两行显示防止溢出
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            (1..4).forEach { day ->
                                FilterChip(
                                    selected = selectedDays.value.contains(day),
                                    onClick = {
                                        val current = selectedDays.value.toMutableSet()
                                        if (current.contains(day)) current.remove(day) else current.add(day)
                                        selectedDays.value = current
                                    },
                                    label = { Text(when(day){ 1->"一" 2->"二" 3->"三" else->"四"}, fontSize = 12.sp) }
                                )
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            (5..7).forEach { day ->
                                FilterChip(
                                    selected = selectedDays.value.contains(day),
                                    onClick = {
                                        val current = selectedDays.value.toMutableSet()
                                        if (current.contains(day)) current.remove(day) else current.add(day)
                                        selectedDays.value = current
                                    },
                                    label = { Text(when(day){ 5->"五" 6->"六" else->"日"}, fontSize = 12.sp) },
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        }
                    }
                    // 对话框底部留白，防止被按钮遮挡
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isBlank() || amount.isBlank()) {
                    nameError = name.isBlank()
                    amountError = amount.isBlank()
                    Toast.makeText(context, "请完善药品信息", Toast.LENGTH_SHORT).show()
                } else {
                    val formattedTime = String.format("%02d:%02d", timePickerState.hour, timePickerState.minute)
                    onConfirm(
                        MedicineReminder(
                            id = initialReminder?.id ?: System.currentTimeMillis(),
                            name = name,
                            timesPerDay = timesPerDay,
                            amount = amount,
                            unit = selectedUnit,
                            time = formattedTime,
                            repeatDays = selectedDays.value,
                            isActive = initialReminder?.isActive ?: true
                        )
                    )
                }
            }) { Text(if (initialReminder == null) "添加" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}