package com.example.test.network

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.test.data.ApiConfig
import com.example.test.data.NetworkRepository
import com.example.test.service.ConnectionTestService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NetworkUiState(
    val configs: List<ApiConfig> = emptyList(),
    val globalPrompt: String = "",
    val showAddEditDialog: Boolean = false,
    val editingConfig: ApiConfig? = null,
    val showDeleteDialog: Boolean = false,
    val deletingConfigId: String? = null,
    val showPromptDialog: Boolean = false,
    val testResults: Map<String, TestResult> = emptyMap()
)

class NetworkViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = NetworkRepository(application)
    private val testService = ConnectionTestService()

    private val _uiState = MutableStateFlow(NetworkUiState())
    val uiState: StateFlow<NetworkUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.configsFlow.collect { configs ->
                _uiState.update { it.copy(configs = configs) }
            }
        }
        viewModelScope.launch {
            repository.globalPromptFlow.collect { prompt ->
                _uiState.update { it.copy(globalPrompt = prompt) }
            }
        }
    }

    // ---- 新增 / 编辑对话框 ----

    fun showAddDialog() {
        _uiState.update { it.copy(showAddEditDialog = true, editingConfig = null) }
    }

    fun showEditDialog(config: ApiConfig) {
        _uiState.update { it.copy(showAddEditDialog = true, editingConfig = config) }
    }

    fun dismissAddEditDialog() {
        _uiState.update { it.copy(showAddEditDialog = false, editingConfig = null) }
    }

    fun saveConfig(config: ApiConfig) {
        viewModelScope.launch {
            val current = _uiState.value.configs
            val isNew = current.none { it.id == config.id }

            val withConfig = if (isNew) current + config
                            else current.map { if (it.id == config.id) config else it }

            // 同一时间只允许一个默认
            val final = if (config.isDefault) {
                withConfig.map { if (it.id == config.id) it else it.copy(isDefault = false) }
            } else {
                withConfig
            }

            repository.saveConfigs(final)
            _uiState.update { it.copy(showAddEditDialog = false, editingConfig = null) }
        }
    }

    // ---- 删除对话框 ----

    fun showDeleteDialog(configId: String) {
        _uiState.update { it.copy(showDeleteDialog = true, deletingConfigId = configId) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = false, deletingConfigId = null) }
    }

    fun confirmDelete() {
        val id = _uiState.value.deletingConfigId ?: return
        viewModelScope.launch {
            val updated = _uiState.value.configs.filter { it.id != id }
            repository.saveConfigs(updated)
            _uiState.update { it.copy(showDeleteDialog = false, deletingConfigId = null) }
        }
    }

    // ---- 设为默认 / 启用切换 ----

    fun setDefault(configId: String) {
        viewModelScope.launch {
            val updated = _uiState.value.configs.map {
                it.copy(isDefault = it.id == configId)
            }
            repository.saveConfigs(updated)
        }
    }

    fun toggleEnabled(configId: String) {
        viewModelScope.launch {
            val updated = _uiState.value.configs.map {
                if (it.id == configId) it.copy(enabled = !it.enabled) else it
            }
            repository.saveConfigs(updated)
        }
    }

    // ---- 全局 Prompt 对话框 ----

    fun showPromptDialog() {
        _uiState.update { it.copy(showPromptDialog = true) }
    }

    fun dismissPromptDialog() {
        _uiState.update { it.copy(showPromptDialog = false) }
    }

    fun saveGlobalPrompt(prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            repository.saveGlobalPrompt(prompt)
            _uiState.update { it.copy(showPromptDialog = false) }
        }
    }

    // ---- 连接测试 ----

    fun testConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(testResults = it.testResults + (config.id to TestResult.Loading))
            }
            val result = testService.test(config)
            _uiState.update {
                it.copy(testResults = it.testResults + (config.id to result))
            }
        }
    }
}
