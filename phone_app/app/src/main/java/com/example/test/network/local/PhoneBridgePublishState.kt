package com.example.test.network.local

data class PhoneBridgePublishState(
    val serverRunning: Boolean = false,
    val published: Boolean = false,
    val port: Int? = null,
    val serviceName: String = "",
    val serviceType: String = "",
    val errorMessage: String? = null
)
