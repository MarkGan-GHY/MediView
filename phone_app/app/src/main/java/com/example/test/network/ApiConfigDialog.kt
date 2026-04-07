package com.example.test.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.test.data.ApiConfig
import com.example.test.data.RequestFormatType

private data class FormState(
    val name: String = "",
    val provider: String = "",
    val model: String = "",
    val endpoint: String = "",
    val apiKey: String = "",
    val promptTemplate: String = "",
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val remark: String = "",
    val requestFormatType: RequestFormatType = RequestFormatType.OPENAI_COMPATIBLE,
    val apiKeyVisible: Boolean = false,
    val formatTypeExpanded: Boolean = false,
    // 校验错误
    val nameError: Boolean = false,
    val modelError: Boolean = false,
    val endpointError: Boolean = false,
    val apiKeyError: Boolean = false
)

private fun ApiConfig?.toFormState() = FormState(
    name = this?.name ?: "",
    provider = this?.provider ?: "",
    model = this?.model ?: "",
    endpoint = this?.endpoint ?: "",
    apiKey = this?.apiKey ?: "",
    promptTemplate = this?.promptTemplate ?: "",
    enabled = this?.enabled ?: true,
    isDefault = this?.isDefault ?: false,
    remark = this?.remark ?: "",
    requestFormatType = this?.requestFormatType ?: RequestFormatType.OPENAI_COMPATIBLE
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiConfigDialog(
    config: ApiConfig?,
    onDismiss: () -> Unit,
    onSave: (ApiConfig) -> Unit
) {
    val isEditing = config != null
    var form by remember(config) { mutableStateOf(config.toFormState()) }

    fun validate(): Boolean {
        form = form.copy(
            nameError = form.name.isBlank(),
            modelError = form.model.isBlank(),
            endpointError = form.endpoint.isBlank(),
            apiKeyError = form.apiKey.isBlank()
        )
        return !form.nameError && !form.modelError && !form.endpointError && !form.apiKeyError
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "编辑配置" else "新增配置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 配置名称
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { form = form.copy(name = it, nameError = false) },
                    label = { Text("配置名称 *") },
                    isError = form.nameError,
                    supportingText = if (form.nameError) {
                        { Text("名称不能为空") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 服务商
                OutlinedTextField(
                    value = form.provider,
                    onValueChange = { form = form.copy(provider = it) },
                    label = { Text("服务商") },
                    placeholder = { Text("如：OpenAI、阿里云…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 协议类型
                ExposedDropdownMenuBox(
                    expanded = form.formatTypeExpanded,
                    onExpandedChange = { form = form.copy(formatTypeExpanded = it) }
                ) {
                    OutlinedTextField(
                        value = form.requestFormatType.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("协议类型 *") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = form.formatTypeExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = form.formatTypeExpanded,
                        onDismissRequest = { form = form.copy(formatTypeExpanded = false) }
                    ) {
                        RequestFormatType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.displayName) },
                                onClick = {
                                    form = form.copy(
                                        requestFormatType = type,
                                        formatTypeExpanded = false
                                    )
                                }
                            )
                        }
                    }
                }

                // 模型名称
                OutlinedTextField(
                    value = form.model,
                    onValueChange = { form = form.copy(model = it, modelError = false) },
                    label = { Text("模型名称 *") },
                    placeholder = { Text("如：gpt-4o、qwen-vl-plus…") },
                    isError = form.modelError,
                    supportingText = if (form.modelError) {
                        { Text("模型名称不能为空") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Endpoint
                OutlinedTextField(
                    value = form.endpoint,
                    onValueChange = { form = form.copy(endpoint = it, endpointError = false) },
                    label = { Text("Endpoint *") },
                    placeholder = { Text("https://api.example.com/v1/chat/completions") },
                    isError = form.endpointError,
                    supportingText = if (form.endpointError) {
                        { Text("Endpoint 不能为空") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // API Key
                OutlinedTextField(
                    value = form.apiKey,
                    onValueChange = { form = form.copy(apiKey = it, apiKeyError = false) },
                    label = { Text("API Key *") },
                    isError = form.apiKeyError,
                    supportingText = if (form.apiKeyError) {
                        { Text("API Key 不能为空") }
                    } else null,
                    visualTransformation = if (form.apiKeyVisible)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = {
                            form = form.copy(apiKeyVisible = !form.apiKeyVisible)
                        }) {
                            Icon(
                                imageVector = if (form.apiKeyVisible)
                                    Icons.Default.VisibilityOff
                                else
                                    Icons.Default.Visibility,
                                contentDescription = if (form.apiKeyVisible) "隐藏" else "显示"
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 自定义 Prompt（可选）
                OutlinedTextField(
                    value = form.promptTemplate,
                    onValueChange = { form = form.copy(promptTemplate = it) },
                    label = { Text("自定义 Prompt（可选，留空则使用全局）") },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                )

                // 备注
                OutlinedTextField(
                    value = form.remark,
                    onValueChange = { form = form.copy(remark = it) },
                    label = { Text("备注") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 启用开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("启用此配置", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = form.enabled,
                        onCheckedChange = { form = form.copy(enabled = it) }
                    )
                }

                // 设为默认开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("设为默认配置", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = form.isDefault,
                        onCheckedChange = { form = form.copy(isDefault = it) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (validate()) {
                    onSave(
                        ApiConfig(
                            id = config?.id ?: java.util.UUID.randomUUID().toString(),
                            name = form.name.trim(),
                            provider = form.provider.trim(),
                            model = form.model.trim(),
                            endpoint = form.endpoint.trim(),
                            apiKey = form.apiKey.trim(),
                            promptTemplate = form.promptTemplate.trim(),
                            enabled = form.enabled,
                            isDefault = form.isDefault,
                            remark = form.remark.trim(),
                            requestFormatType = form.requestFormatType
                        )
                    )
                }
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
