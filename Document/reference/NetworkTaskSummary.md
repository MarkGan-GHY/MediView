# Network 模块任务总结

## 完成日期
2026-04-07

## 任务目标
在 Network 页面骨架上完成"大模型调用配置"管理页面，实现用户配置模型、选择模型的完整闭环。

---

## 新增 / 修改文件清单

### 新增文件

| 文件路径 | 说明 |
|---|---|
| `app/src/main/java/com/example/test/data/ApiConfig.kt` | API 配置数据模型 + `RequestFormatType` 枚举（OPENAI_COMPATIBLE / QWEN / GEMINI / CUSTOM）|
| `app/src/main/java/com/example/test/data/NetworkRepository.kt` | DataStore 持久化层：读写配置列表（Gson 序列化）和全局 Prompt |
| `app/src/main/java/com/example/test/network/NetworkViewModel.kt` | AndroidViewModel + NetworkUiState + StateFlow，封装全部业务逻辑 |
| `app/src/main/java/com/example/test/network/ApiConfigCard.kt` | 配置列表 Card 组件：展示 name/provider/model/endpoint/协议/默认/启用状态，DropdownMenu 操作 |
| `app/src/main/java/com/example/test/network/ApiConfigDialog.kt` | 新增/编辑表单对话框：全字段输入、基础校验、API Key 显隐切换 |
| `app/src/main/java/com/example/test/network/DeleteConfirmDialog.kt` | 删除二次确认对话框 |
| `app/src/main/java/com/example/test/network/PromptEditDialog.kt` | 全局 Prompt 编辑对话框，不允许保存空内容 |

### 修改文件

| 文件路径 | 修改内容 |
|---|---|
| `app/src/main/java/com/example/test/NetworkScreen.kt` | 接入 NetworkViewModel，FAB 触发新增，Prompt Card 触发编辑，添加 LazyColumn 配置列表，注册三个对话框 |
| `app/build.gradle.kts` | 新增 `datastore-preferences:1.1.1`、`lifecycle-viewmodel-compose:2.8.5`、`material-icons-extended` |

---

## 数据模型说明

```
ApiConfig
├── id: String (UUID)
├── name: String          配置显示名称
├── provider: String      服务商名称（自由填写）
├── model: String         模型名称（如 gpt-4o）
├── endpoint: String      完整 API URL
├── apiKey: String        鉴权密钥
├── promptTemplate: String 覆盖全局 Prompt（留空则用全局）
├── enabled: Boolean      是否启用
├── isDefault: Boolean    是否为默认（全局唯一）
├── remark: String        备注
└── requestFormatType: RequestFormatType
```

---

## 后续接入真实请求层的入口

真实的大模型调用逻辑应新建在：

```
app/src/main/java/com/example/test/service/LlmApiService.kt
```

调用时从 `NetworkRepository.configsFlow` 读取默认配置（`isDefault == true && enabled == true`），
然后按 `requestFormatType` 分支构造请求体：

```kotlin
// 伪代码示意
val defaultConfig = repository.configsFlow.first().firstOrNull { it.isDefault && it.enabled }
val prompt = defaultConfig?.promptTemplate?.takeIf { it.isNotBlank() }
             ?: repository.globalPromptFlow.first()

when (defaultConfig?.requestFormatType) {
    OPENAI_COMPATIBLE -> buildOpenAiRequest(defaultConfig, prompt, imageBase64)
    QWEN              -> buildQwenRequest(defaultConfig, prompt, imageBase64)
    GEMINI            -> buildGeminiRequest(defaultConfig, prompt, imageBase64)
    CUSTOM            -> buildCustomRequest(defaultConfig, prompt, imageBase64)
}
```

在 `LocalHttpServer.kt` 的 `/analyzeDrug` 路由处理函数中，将当前写死的模拟返回替换为对 `LlmApiService` 的真实调用即可，其余架构无需改动。
