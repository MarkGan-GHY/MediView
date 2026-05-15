package com.example.test.server

import com.example.test.data.DrugAnalyzeResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片识别任务的进程内单例存储。
 *
 * 协议：
 *  - 眼镜端 POST /analyzeDrug 上传图片 → 立刻 submit() 拿 taskId
 *  - 后台协程跑 LLM，完成时调用 markDone / markError
 *  - 眼镜端 GET /analyzeResult?taskId=xxx 轮询，按 status 字段决定继续等还是停止
 *
 * 内存生命周期足够长（识别结果只在拍照后短时间内有用），不做持久化。
 * cleanup() 在每次访问时顺手清理 > [TTL_MS] 的已完成任务，避免无界增长。
 */
object AnalyzeTaskStore {

    enum class Status { RUNNING, DONE, ERROR }

    data class Task(
        val taskId: String,
        @Volatile var status: Status,
        val startMs: Long,
        @Volatile var result: DrugAnalyzeResponse? = null,
        @Volatile var error: String? = null
    ) {
        @Volatile var finishedAtMs: Long = 0
    }

    /** 已完成任务保留 5 分钟即可被清理。轮询超时是 90s，留足余量。 */
    private const val TTL_MS = 5 * 60 * 1000L

    private val tasks = ConcurrentHashMap<String, Task>()

    /** 创建一条 RUNNING 状态的任务，返回 taskId。*/
    fun submit(): String {
        cleanup()
        val taskId = UUID.randomUUID().toString()
        tasks[taskId] = Task(
            taskId = taskId,
            status = Status.RUNNING,
            startMs = System.currentTimeMillis()
        )
        return taskId
    }

    fun markDone(taskId: String, result: DrugAnalyzeResponse) {
        tasks[taskId]?.apply {
            this.result = result
            this.status = Status.DONE
            this.finishedAtMs = System.currentTimeMillis()
        }
    }

    fun markError(taskId: String, error: String) {
        tasks[taskId]?.apply {
            this.error = error
            this.status = Status.ERROR
            this.finishedAtMs = System.currentTimeMillis()
        }
    }

    fun get(taskId: String): Task? {
        cleanup()
        return tasks[taskId]
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        val it = tasks.entries.iterator()
        while (it.hasNext()) {
            val t = it.next().value
            if (t.status != Status.RUNNING && now - t.finishedAtMs > TTL_MS) {
                it.remove()
            }
        }
    }
}
