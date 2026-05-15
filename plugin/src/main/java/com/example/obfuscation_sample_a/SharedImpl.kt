package com.example.obfuscation_sample_a

import com.example.shared_module.Shared
import kotlinx.coroutines.delay

class SharedImpl : Shared {
    override suspend fun doSomething(params: Int): String {
        delay(10_000)
        return "$params"
    }
}