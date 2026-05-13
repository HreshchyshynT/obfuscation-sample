package com.example.obfuscation_sample_a.db

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach

class DbDataManager(private val itemDao: ItemDao) {

    fun getItems(): Flow<List<Item>> {
        // to keep method
        foo()
        return itemDao.getAllItems().onEach {
            delay(1000)
        }
    }

    suspend fun getItem() {
        itemDao.getAllItems().first()
    }

    suspend fun addItem(value: String) {
        delay(1000)
        itemDao.insert(Item(value = value))
    }

    companion object {
        fun foo(): String {
            return "bar"
        }
    }
}
