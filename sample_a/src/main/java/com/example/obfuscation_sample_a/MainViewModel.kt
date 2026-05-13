package com.example.obfuscation_sample_a

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.obfuscation_sample_a.db.AppDatabase
import com.example.obfuscation_sample_a.db.DbDataManager
import com.example.obfuscation_sample_a.db.Item
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val dataManager = DbDataManager(db.itemDao())

    val items: StateFlow<List<Item>> = dataManager.getItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addItem(value: String) {
        viewModelScope.launch {
            dataManager.addItem(value)
        }
    }
}
