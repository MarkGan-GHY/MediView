package com.example.test.data

/**
 * 手机端返回给眼镜端的药物识别结果。
 *
 * 当前阶段返回固定的模拟数据，后续接入真实识别模型时，
 * 只需在 [com.example.test.server.LocalHttpServer] 中替换构造逻辑，
 * 本数据类无需修改。
 *
 * @param success            请求是否成功处理
 * @param drugName           识别出的药物名称
 * @param usage              用法（如"温水送服"、"擦拭于创口处"）
 * @param dosage             用量（如"一日三次，每次一片"）
 * @param contraindications  禁忌（如"不宜空腹服用"、"不宜与阿司匹林共服"）
 */
data class DrugAnalyzeResponse(
    val success: Boolean,
    val drugName: String,
    val usage: String,
    val dosage: String,
    val contraindications: String
)
