package com.example.test.network

sealed class TestResult {
    object Idle : TestResult()
    object Loading : TestResult()
    data class Success(val latencyMs: Long) : TestResult()
    data class Failure(val message: String) : TestResult()
}
