package com.example.test.data

/**
 * 手机端返回给眼镜端的药物识别结果。
 *
 * 当前阶段返回固定的模拟数据，后续接入真实识别模型时，
 * 只需在 [com.example.test.server.LocalHttpServer] 中替换构造逻辑，
 * 本数据类无需修改。
 *
 * @param success      请求是否成功处理
 * @param drugName     识别出的药物名称
 * @param confidence   识别置信度，0.0 ~ 1.0
 * @param warningText  给用户的提示或警告信息
 * @param needConfirm  是否需要用户二次确认（如剂量风险时为 true）
 */
data class DrugAnalyzeResponse(
    val success: Boolean,
    val drugName: String,
    val confidence: Double,
    val warningText: String,
    val needConfirm: Boolean
)
