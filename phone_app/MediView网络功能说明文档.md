# MediView 网络功能说明文档

> 适用版本：当前 Demo 阶段（手机端 HTTP 服务 + 大模型调用）
> 面向读者：后续参与开发的工程师

---

## 一、系统角色与通信架构

MediView 采用三端协同架构：**眼镜端**（RayNeo X3 Pro）、**手机端**（本项目）、**监护人端**（待开发）。当前阶段只实现眼镜端与手机端的通信闭环。

```
眼镜端                    手机端                      大模型云端
  │                         │                            │
  │── POST /analyzeDrug ──► │                            │
  │     (JPEG 二进制)        │── HTTPS 请求 ──────────► │
  │                         │   (Base64 图片 + Prompt)   │
  │                         │ ◄── JSON 响应 ────────── │
  │ ◄── JSON 响应 ────────  │   (模型输出文本)           │
  │   {drugName, warning…}  │                            │
```

**手机端作为服务端的原因：**
- 眼镜端资源受限，只负责拍照和显示
- 手机开热点后局域网 IP 固定（通常 `192.168.43.1`），眼镜接入热点后直接访问，无需服务发现
- 手机负责所有计算密集型工作：接收图片、调用 API、解析结果

---

## 二、全链路流程总览

```
[1] 眼镜拍照
      │ JPEG 原始字节
      ▼
[2] NanoHTTPD 接收 POST /analyzeDrug
      │ 按 content-length 读取字节流
      ▼
[3] 保存图片到本地缓存（调试用）
      │ ByteArray
      ▼
[4] 广播通知 UI 更新请求日志
      │ ByteArray（不变）
      ▼
[5] LlmApiService.analyze()
      ├─ 读取默认 ApiConfig（DataStore）
      ├─ 拼接 Prompt + JSON 格式约束
      ├─ Base64 编码图片
      ├─ 按协议类型构造请求体
      └─ OkHttp 发送 HTTPS 请求
      │ 大模型原始响应 JSON
      ▼
[6] 提取模型输出文本
      │ 文本字符串
      ▼
[7] 解析为 DrugAnalyzeResponse
      │ 结构化对象
      ▼
[8] Gson 序列化为 JSON 字符串
      │ HTTP 200 响应体
      ▼
[9] 眼镜端接收并显示
```

---

## 三、各阶段详解

### 阶段 1–3：图片从眼镜端传入手机

**涉及文件：** `server/LocalHttpServer.kt`、`service/HttpServerService.kt`

眼镜端向固定地址发送 POST 请求：

```
POST http://192.168.43.1:8080/analyzeDrug
Content-Type: image/jpeg
Content-Length: <字节数>

<JPEG 原始二进制>
```

手机端 `HttpServerService` 是一个 Android **前台服务**，在后台持续运行（防止系统杀死进程），内部持有 `LocalHttpServer`（NanoHTTPD 实例）监听 `0.0.0.0:8080`。

`LocalHttpServer` 收到请求后，不调用 NanoHTTPD 的 `parseBody()`（避免临时文件开销），而是直接从 `session.inputStream` 按 `content-length` 循环读取原始字节：

```kotlin
// LocalHttpServer.kt : readImageBytes()
val buffer = ByteArray(contentLength)
while (totalRead < contentLength) {
    val read = inputStream.read(buffer, totalRead, contentLength - totalRead)
    if (read == -1) break
    totalRead += read
}
```

读取完成后将图片同步写入缓存目录（`/cache/drug_images/img_<时间戳>.jpg`），便于 adb 调试时取出原始图片。

> **后续开发注意：** 图片保存步骤可在接入正式识别后删除，或改为异步写入避免阻塞响应。

---

### 阶段 4：通知 UI 更新日志

**涉及文件：** `service/HttpServerService.kt`、`ServiceScreen.kt`

`LocalHttpServer` 通过回调（`onRequestReceived`）在 NanoHTTPD 工作线程中触发广播：

```kotlin
// HttpServerService.kt
onRequestReceived = { imageSize, savePath ->
    broadcastRequestReceived(imageSize, savePath)
}
```

`ServiceScreen` 注册了 `BroadcastReceiver`，收到广播后在主线程追加一条日志到 `logMessages` 列表，自动刷新 UI。此步骤与后续识别流程**并行**，不影响响应时序。

---

### 阶段 5：调用大模型 API

**涉及文件：** `service/LlmApiService.kt`、`data/NetworkRepository.kt`、`data/ApiConfig.kt`

#### 5.1 读取配置

`LlmApiService` 从 DataStore 读取标记为 **isDefault=true 且 enabled=true** 的 `ApiConfig`：

```kotlin
val config = repository.configsFlow.first()
    .firstOrNull { it.isDefault && it.enabled }
    ?: return errorResponse("未找到可用的默认 API 配置…")
```

`ApiConfig` 包含发起请求所需的全部信息：

| 字段 | 用途 |
|---|---|
| `endpoint` | API 完整 URL |
| `apiKey` | 鉴权密钥 |
| `model` | 模型名称（如 `gpt-4o`） |
| `requestFormatType` | 协议类型（决定请求体结构） |
| `promptTemplate` | 覆盖全局 Prompt（为空则用全局） |

#### 5.2 构造 Prompt

优先使用该配置的 `promptTemplate`，否则读取全局 Prompt（DataStore 存储，用户可在 Network 页编辑）。在其末尾**动态追加** JSON 格式约束，不写入 DataStore：

```
<用户编写的系统 Prompt>

请严格以如下 JSON 格式返回，不要包含任何其他文字或 Markdown 标记：
{"drugName":"药物名称","confidence":0.9,"warningText":"注意事项摘要（100字以内）","needConfirm":false}
```

> **为什么追加而不写死：** 用户的 Prompt 决定了识别风格和语气，JSON 格式约束是技术层面的要求，两者分开管理，互不影响。

#### 5.3 图片 Base64 编码

```kotlin
val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
```

`NO_WRAP` 生成无换行符的单行字符串，可直接嵌入 JSON 字段。

#### 5.4 按协议类型构造请求体

根据 `requestFormatType` 分支，使用 Gson `JsonObject` 构建器构造请求体（自动处理字符串转义，避免手动拼接 JSON 的安全问题）：

---

**OPENAI_COMPATIBLE / CUSTOM**

```json
{
  "model": "<model>",
  "messages": [
    { "role": "system", "content": "<fullPrompt>" },
    {
      "role": "user",
      "content": [
        { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,<base64>" } },
        { "type": "text", "text": "请识别图片中的药物" }
      ]
    }
  ],
  "response_format": { "type": "json_object" }
}
```

请求头：`Authorization: Bearer <apiKey>`

`response_format: json_object` 是 **API 层面的 JSON 强制模式**，不依赖 Prompt 即可约束输出格式。

---

**QWEN（通义千问 DashScope 原生格式）**

```json
{
  "model": "<model>",
  "input": {
    "messages": [
      { "role": "system", "content": [{ "text": "<fullPrompt>" }] },
      {
        "role": "user",
        "content": [
          { "image": "data:image/jpeg;base64,<base64>" },
          { "text": "请识别图片中的药物" }
        ]
      }
    ]
  },
  "parameters": {}
}
```

请求头：`Authorization: Bearer <apiKey>`

> **注意：** Qwen 原生格式不支持 `response_format`，JSON 输出仅靠 Prompt 约束，存在格式不稳定的风险。后续可考虑改用 DashScope 的 OpenAI 兼容端点。

---

**GEMINI**

```json
{
  "contents": [{
    "parts": [
      { "inline_data": { "mime_type": "image/jpeg", "data": "<base64>" } },
      { "text": "<fullPrompt>\n\n请识别图片中的药物" }
    ]
  }],
  "generationConfig": { "response_mime_type": "application/json" }
}
```

鉴权方式与其他不同：API Key 拼接在 URL 参数 `?key=<apiKey>`，不使用 Authorization 头。

`response_mime_type: application/json` 是 Gemini 的 API 层面 JSON 强制模式。

---

#### 5.5 发送请求

使用 OkHttp，超时设置：

| 阶段 | 超时 |
|---|---|
| 连接 | 15 秒 |
| 写入（上传图片） | 30 秒 |
| 读取（等待模型响应） | 60 秒 |

HTTP 非 2xx 时抛出异常，由 `try/catch` 捕获并返回错误响应。

---

### 阶段 6：提取模型输出文本

各协议响应结构不同，从对应路径提取内容字段：

| 协议 | JSON 提取路径 |
|---|---|
| OpenAI 兼容 | `choices[0].message.content` |
| Qwen | `output.choices[0].message.content[0].text` |
| Gemini | `candidates[0].content.parts[0].text` |

---

### 阶段 7：解析模型输出为结构化对象

**涉及文件：** `LlmApiService.parseModelOutput()`、`data/DrugAnalyzeResponse.kt`

目标数据结构：

```kotlin
data class DrugAnalyzeResponse(
    val success: Boolean,    // 本次请求是否成功
    val drugName: String,    // 识别出的药物名称
    val confidence: Double,  // 置信度 0.0~1.0
    val warningText: String, // 注意事项摘要（展示给用户）
    val needConfirm: Boolean // 是否需要眼镜端显示二次确认交互
)
```

解析流程：

```
模型输出文本
    │
    ├─ 去除可能的 Markdown 包裹（```json … ```）
    │
    ├─ Gson 解析为 JsonObject
    │       ├─ 成功 → 映射各字段，构造 DrugAnalyzeResponse
    │       └─ 失败 → 降级：原始文本截取前 300 字放入 warningText
    │                        drugName = "识别完成"，needConfirm = true
    │
    └─ DrugAnalyzeResponse
```

**降级策略的意义：** 即使模型未遵循 JSON 格式，眼镜端也不会崩溃，用户仍能看到模型的原始输出（显示在 `warningText` 区域），并通过 `needConfirm=true` 提示其手动确认。

---

### 阶段 8–9：封装并返回给眼镜端

`LocalHttpServer` 将 `DrugAnalyzeResponse` 序列化为 JSON，作为 HTTP 200 响应体同步返回给眼镜端（NanoHTTPD 的工作线程会在 `runBlocking` 中等待 LLM 调用完成）：

```json
{
  "success": true,
  "drugName": "阿莫西林胶囊",
  "confidence": 0.92,
  "warningText": "青霉素类抗生素，对青霉素过敏者禁用。常见副作用：腹泻、皮疹。",
  "needConfirm": false
}
```

**眼镜端根据字段决定显示逻辑（约定，眼镜端自行实现）：**

| 字段 | 眼镜端行为 |
|---|---|
| `success: false` | 显示错误提示（如"未配置 API"） |
| `drugName` | 主标题 |
| `warningText` | 正文说明区域 |
| `confidence` | 可选显示置信度百分比 |
| `needConfirm: true` | 显示"需要确认"交互，等待用户操作 |

**无配置兜底：** 若 Network 页未配置任何 API，`llmApiService` 为 `null`，`LocalHttpServer` 回退到 `buildMockResponse()` 返回固定测试数据，服务不中断，便于在没有 API Key 时调试眼镜通信链路。

---

## 四、关键文件索引

| 文件 | 职责 |
|---|---|
| `service/HttpServerService.kt` | 前台 Service 宿主，持有 HTTP 服务器和 LlmApiService 生命周期 |
| `server/LocalHttpServer.kt` | 接收眼镜 HTTP 请求，读取图片字节，调用 LLM，返回 JSON 响应 |
| `service/LlmApiService.kt` | 读取配置，编码图片，按协议构造请求，解析响应 |
| `service/ConnectionTestService.kt` | 最小化连接测试（不带图片），供 Network 页"测试连接"功能使用 |
| `data/NetworkRepository.kt` | DataStore 持久化：API 配置列表 + 全局 Prompt |
| `data/ApiConfig.kt` | API 配置数据模型，含 RequestFormatType 枚举 |
| `data/DrugAnalyzeResponse.kt` | 手机端返回给眼镜端的响应数据结构 |

---

## 五、后续开发指引

### 接入新的大模型协议

1. 在 `ApiConfig.kt` 的 `RequestFormatType` 枚举中新增类型
2. 在 `LlmApiService.kt` 的 `when` 分支中新增对应方法，实现请求构造和响应解析
3. 在 `ConnectionTestService.kt` 的 `when` 分支中新增对应最小化测试请求

### 扩展返回字段

修改 `DrugAnalyzeResponse.kt` 新增字段，同步修改：
- `LlmApiService.parseModelOutput()` 中的字段映射
- `LlmApiService.JSON_FORMAT_INSTRUCTION` 中的格式示例，让模型返回新字段
- 眼镜端解析逻辑

### 接入本地模型（离线识别）

在 `LocalHttpServer.handleAnalyzeDrug()` 中，将 `llmApiService?.analyze()` 替换为本地推理调用（TFLite/ONNX），返回值类型保持 `DrugAnalyzeResponse` 不变，眼镜端无需任何改动。

### 记录历史识别记录

在 `LocalHttpServer.handleAnalyzeDrug()` 的 `onRequestReceived` 回调之后、返回响应之前插入数据库写入逻辑（Room DAO），不影响响应时序。

### Prompt JSON 格式稳定性

当前 Qwen 协议仅靠 Prompt 约束 JSON 输出，稳定性弱于 OpenAI/Gemini 的 API 层模式。可考虑：
- 改用 DashScope 的 OpenAI 兼容端点（`https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`），直接走 `OPENAI_COMPATIBLE` 协议
- 或在 Qwen 的 `parameters` 中添加 `"result_format"` 等参数增强约束
