package com.example.glasstest

import android.app.Application
import android.util.Log
import com.example.glasstest.service.ReminderPollingService
import com.ffalcon.mercury.android.sdk.MercurySDK

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("DEBUG","Application Started")
        // 初始化 SDK
        MercurySDK.init(this)
        Log.d("DEBUG","Mercury SDK Started")

        // 启动服药提醒轮询服务（独立于 DrugCaptureActivity 生命周期）
        ReminderPollingService.start(this)
    }
}