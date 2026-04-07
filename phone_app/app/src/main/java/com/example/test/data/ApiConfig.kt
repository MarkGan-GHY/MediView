package com.example.test.data

import java.util.UUID

enum class RequestFormatType(val displayName: String) {
    OPENAI_COMPATIBLE("OpenAI 兼容"),
    QWEN("通义千问"),
    GEMINI("Gemini"),
    CUSTOM("自定义")
}

data class ApiConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val provider: String = "",
    val model: String = "",
    val endpoint: String = "",
    val apiKey: String = "",
    val promptTemplate: String = "",
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val remark: String = "",
    val requestFormatType: RequestFormatType = RequestFormatType.OPENAI_COMPATIBLE
)
