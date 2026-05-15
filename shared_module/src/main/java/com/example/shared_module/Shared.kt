package com.example.shared_module

interface Shared {
    suspend fun doSomething(params: Int): String
}
