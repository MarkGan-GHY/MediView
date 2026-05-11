package com.example.test.ui.reminder

import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.test.model.MedicineReminder
import com.example.test.viewmodel.ReminderViewModel

@Composable
fun ReminderScreen() {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var reminderToDelete by remember { mutableStateOf<MedicineReminder?>(null) }
    var reminderToEdit by remember { mutableStateOf<MedicineReminder?>(null) }
    var lastOperatedId by remember { mutableLongStateOf(-1L) }

    val listState = rememberLazyListState()
    val viewModel: ReminderViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return ReminderViewModel(context.applicationContext) as T
            }
        }
    )

    val sortedReminders by remember {
        derivedStateOf {
            viewModel.reminders.sortedWith(
                compareByDescending<MedicineReminder> { it.isActive }
                    .thenBy { it.times.firstOrNull() ?: "23:59" }
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
            Text(
                text = "服药提醒清单",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
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
                                placementSpec = spring(
                                    stiffness = 300f,
                                    dampingRatio = 0.8f
                                )
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
                text = { Text("确定要删除\u201C${reminderToDelete?.name}\u201D吗？") },
                confirmButton = {
                    TextButton(onClick = {
                        reminderToDelete?.let { viewModel.deleteReminder(it) }
                        reminderToDelete = null
                    }) { Text("删除", color = Color.Red) }
                },
                dismissButton = {
                    TextButton(onClick = { reminderToDelete = null }) { Text("取消") }
                }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Notifications,
                        null,
                        tint = if (reminder.isActive) MaterialTheme.colorScheme.primary
                        else Color.Gray
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // 多时间横向排列，超过 2 个时缩小字号
                    val timesDisplay = reminder.times.joinToString("  ")
                    Text(
                        text = timesDisplay,
                        fontSize = if (reminder.times.size > 2) 22.sp else 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (reminder.isActive) Color.Unspecified else Color.Gray
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "编辑",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = Color.Red.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = reminder.isActive,
                        onCheckedChange = { onActiveChange(it) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = reminder.name,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (reminder.isActive) MaterialTheme.colorScheme.onSurface
                else Color.Gray
            )
            Text(
                text = "用法：一天 ${reminder.timesPerDay} 次，一次 ${reminder.amount} ${reminder.unit}",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = if (reminder.isActive) MaterialTheme.colorScheme.secondary
                else Color.Gray
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val weekDays = listOf("一", "二", "三", "四", "五", "六", "日")
                weekDays.forEachIndexed { index, day ->
                    val isSelected = reminder.repeatDays.contains(index + 1)
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (isSelected && reminder.isActive)
                            MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent,
                        border = if (isSelected) null
                        else BorderStroke(1.dp, Color.LightGray)
                    ) {
                        Text(
                            text = day,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 12.sp,
                            color = if (isSelected && reminder.isActive)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else Color.Gray
                        )
                    }
                }
            }
        }
    }
}
