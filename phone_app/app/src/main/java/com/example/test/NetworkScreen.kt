package com.example.test

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.test.network.ApiConfigCard
import com.example.test.network.ApiConfigDialog
import com.example.test.network.DeleteConfirmDialog
import com.example.test.network.NetworkViewModel
import com.example.test.network.PromptEditDialog

@Composable
fun NetworkScreen() {
    val viewModel: NetworkViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::showAddDialog) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_input_add),
                    contentDescription = "添加配置"
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // ---- 顶部功能区 ----
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(136.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                // AI 能力设置（暂未开发）
                Card(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(end = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                "AI 能力设置",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "开发中",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }

                // 预设 Prompt 编辑（可点击）
                Card(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(start = 4.dp)
                        .clickable { viewModel.showPromptDialog() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                "预设 Prompt",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                uiState.globalPrompt,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Divider(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Spacer(modifier = Modifier.height(4.dp))

            // ---- 配置列表 ----
            if (uiState.configs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "还没有 API 配置\n点击右下角 + 新增",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.configs, key = { it.id }) { config ->
                        ApiConfigCard(
                            config = config,
                            onEdit = { viewModel.showEditDialog(config) },
                            onDelete = { viewModel.showDeleteDialog(config.id) },
                            onSetDefault = { viewModel.setDefault(config.id) },
                            onToggleEnabled = { viewModel.toggleEnabled(config.id) }
                        )
                    }
                }
            }
        }

        // ---- 对话框 ----
        if (uiState.showAddEditDialog) {
            ApiConfigDialog(
                config = uiState.editingConfig,
                onDismiss = viewModel::dismissAddEditDialog,
                onSave = viewModel::saveConfig
            )
        }

        if (uiState.showDeleteDialog) {
            DeleteConfirmDialog(
                onConfirm = viewModel::confirmDelete,
                onDismiss = viewModel::dismissDeleteDialog
            )
        }

        if (uiState.showPromptDialog) {
            PromptEditDialog(
                currentPrompt = uiState.globalPrompt,
                onDismiss = viewModel::dismissPromptDialog,
                onSave = viewModel::saveGlobalPrompt
            )
        }
    }
}

@Preview
@Composable
fun PreviewNetworkScreen() {
    MaterialTheme {
        NetworkScreen()
    }
}
