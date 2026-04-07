# 网络请求逻辑概述

## 整体链路

```
眼镜端拍照
    │
    │  POST /analyzeDrug  (image/jpeg 二进制)
    │  局域网 HTTP，端口 8080
    ▼
手机端 NanoHTTPD 服务器
    │
    ├─ 读取图片字节
    ├─ 保存缓存（调试用）
    ├─ 广播通知 UI 更新日志
    │
    │  Base64 编码 + 构造请求体
    ▼
大模型 API（OpenAI / Qwen / Gemini / Custom）
    │
    │  返回 JSON 文本
    ▼
手机端解析响应
    │
    │  HTTP 200 + JSON
    ▼
眼镜端接收结果并显示
```

---

## 第一段：眼镜 → 手机（图片传入）

**协议：** HTTP POST，Content-Type: `image/jpeg`，Body 为 JPEG 原始二进制。

**接收端：** `server/LocalHttpServer.kt`，基于 NanoHTTPD，监听 `0.0.0.0:8080`。

```
POST http://192.168.43.1:8080/analyzeDrug
Content-Type: image/jpeg
Content-Length: <字节数>

<JPEG 二进制数据>
```

手机开启热点后 IP 固定为 `192.168.43.1`（Android 标准热点地址），眼镜连入热点后直接访问该地址，无需服务发现。

**读取方式（`readImageBytes`）：**

NanoHTTPD 不调用 `parseBody()` 时，请求 body 在 `session.inputStream` 中。代码按 `content-length` 循环读取，直到读满或流结束：

```kotlin
// LocalHttpServer.kt : readImageBytes()
val buffer = ByteArray(contentLength)
while (totalRead < contentLength) {
    val read = inputStream.read(buffer, totalRead, contentLength - totalRead)
    if (read == -1) break
    totalRead += read
}
```

读取完成后图片以 `ByteArray` 形式在内存中，同时写入缓存目录 `/cache/drug_images/img_<时间戳>.jpg` 供调试。

---

## 第二段：手机 → 大模型 API（图片发送）

**入口：** `service/LlmApiService.kt`，`analyze(imageBytes: ByteArray)` 方法。

### 步骤 1：读取配置

从 DataStore 读取标记为"默认且启用"的 `ApiConfig`，获取：
- `endpoint`：API 地址
- `apiKey`：鉴权密钥
- `model`：模型名称
- `requestFormatType`：协议类型
- `promptTemplate`（优先）或全局 Prompt（兜底）

### 步骤 2：构造 Prompt

用户在 Network 页面编辑的 Prompt 作为 system 指令，代码在其末尾动态追加 JSON 格式约束（不写入 DataStore，不影响用户编辑内容）：

```
<用户 Prompt>

请严格以如下 JSON 格式返回，不要包含任何其他文字或 Markdown 标记：
{"drugName":"药物名称","confidence":0.9,"warningText":"注意事项摘要（100字以内）","needConfirm":false}
```

### 步骤 3：图片 Base64 编码

```kotlin
val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
```

`NO_WRAP` 不插入换行符，生成单行字符串，直接嵌入 JSON 请求体。

### 步骤 4：按协议类型构造请求体

#### OpenAI 兼容（含 Custom）

```json
{
  "model": "<model>",
  "messages": [
    { "role": "system", "content": "<prompt>" },
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

鉴权：`Authorization: Bearer <apiKey>`

#### 通义千问（DashScope 原生）

```json
{
  "model": "<model>",
  "input": {
    "messages": [
      { "role": "system", "content": [{ "text": "<prompt>" }] },
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

鉴权：`Authorization: Bearer <apiKey>`

#### Gemini

```json
{
  "contents": [{
    "parts": [
      { "inline_data": { "mime_type": "image/jpeg", "data": "<base64>" } },
      { "text": "<prompt>\n\n请识别图片中的药物" }
    ]
  }],
  "generationConfig": { "response_mime_type": "application/json" }
}
```

鉴权：API Key 拼接在 URL 参数 `?key=<apiKey>`，不使用 Authorization 头。

**HTTP 客户端：** OkHttp，超时设置：连接 15s / 读取 60s / 写入 30s。

---

## 第三段：大模型响应 → 解析为结构化数据

**入口：** `LlmApiService.parseModelOutput(text: String)`

各协议从不同路径提取模型输出的文本内容：

| 协议 | 响应路径 |
|---|---|
| OpenAI 兼容 | `choices[0].message.content` |
| Qwen | `output.choices[0].message.content[0].text` |
| Gemini | `candidates[0].content.parts[0].text` |

提取到文本后，先去除可能的 Markdown 代码块包裹（` ```json ... ``` `），再解析为 `DrugAnalyzeResponse`：

```kotlin
data class DrugAnalyzeResponse(
    val success: Boolean,
    val drugName: String,      // 药物名称
    val confidence: Double,    // 识别置信度 0.0~1.0
    val warningText: String,   // 注意事项摘要
    val needConfirm: Boolean   // 是否需要用户二次确认
)
```

**降级策略：** 若模型未遵循 JSON 格式，解析失败时将原始文本截取前 300 字放入 `warningText`，`success` 仍为 `true`，`needConfirm` 设为 `true` 提示用户注意。

---

## 第四段：手机 → 眼镜（结果返回）

`LocalHttpServer` 将 `DrugAnalyzeResponse` 序列化为 JSON，作为 HTTP 200 响应体返回给眼镜端：

```json
{
  "success": true,
  "drugName": "阿莫西林胶囊",
  "confidence": 0.92,
  "warningText": "青霉素类抗生素，对青霉素过敏者禁用。常见副作用：腹泻、皮疹。",
  "needConfirm": false
}
```

眼镜端收到响应后，根据字段决定显示内容：
- `drugName`：主标题
- `warningText`：正文提示
- `needConfirm = true`：显示二次确认交互
- `success = false`：显示错误提示（如"未配置 API"）

**无配置兜底：** 若 Network 页面未配置任何 API，`llmApiService` 为 `null`，`LocalHttpServer` 回退调用 `buildMockResponse()` 返回固定模拟数据，服务不中断。

---

## 关键文件索引

| 文件 | 职责 |
|---|---|
| `server/LocalHttpServer.kt` | 接收眼镜 HTTP 请求，读取图片字节，返回 JSON 响应 |
| `service/LlmApiService.kt` | 读取配置，编码图片，调用大模型 API，解析响应 |
| `service/HttpServerService.kt` | 前台 Service 宿主，创建并持有 LlmApiService 实例 |
| `data/NetworkRepository.kt` | DataStore 持久化：API 配置列表 + 全局 Prompt |
| `data/ApiConfig.kt` | API 配置数据模型，含 RequestFormatType 枚举 |
| `data/DrugAnalyzeResponse.kt` | 眼镜端与手机端之间的响应数据结构 |
