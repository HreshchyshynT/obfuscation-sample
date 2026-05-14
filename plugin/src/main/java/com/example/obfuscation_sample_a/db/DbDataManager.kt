package com.example.obfuscation_sample_a.db

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DbDataManager {

    private val list = mutableListOf<Item>()

    fun observeItems(): Flow<List<Item>> {
        return flow {
            delay(1000)
            emit(list.toList())
        }
    }

    suspend fun getItems(): List<Item> {
        delay(1000)
        return list.toList()
    }

    suspend fun addItem(value: String) {
        delay(1000)
        list.add(Item(id = list.size, value = value))
    }
}
