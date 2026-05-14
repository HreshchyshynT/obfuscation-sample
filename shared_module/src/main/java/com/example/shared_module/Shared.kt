package com.example.shared_module

interface Shared<P, R> {
    suspend fun doSomething(params: P): Result<R>
}
