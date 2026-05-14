package com.example.obfuscation_sample_a

import androidx.annotation.Keep
import com.example.shared_module.Shared
import kotlinx.coroutines.delay

@Keep
class SharedImpl: Shared<Int, String> {
    override suspend fun doSomething(params: Int): Result<String> {
        delay(10_000)
        return Result.success("$params")
    }
}