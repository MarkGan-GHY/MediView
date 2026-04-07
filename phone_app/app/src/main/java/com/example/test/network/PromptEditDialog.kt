package com.example.test.network

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PromptEditDialog(
    currentPrompt: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(currentPrompt) { mutableStateOf(currentPrompt) }
    val isError = text.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑预设 Prompt") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Prompt 内容") },
                placeholder = { Text("请输入发送给大模型的系统提示词…") },
                isError = isError,
                supportingText = if (isError) {
                    { Text("Prompt 不能为空") }
                } else null,
                minLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (!isError) onSave(text) },
                enabled = !isError
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
