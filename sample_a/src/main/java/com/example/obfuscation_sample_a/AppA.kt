package com.example.obfuscation_sample_a

import android.app.Application
import com.example.obfuscation_sample_a.db.AppDatabase
import android.util.Log

class AppA : Application() {

    lateinit var db: AppDatabase

    override fun onCreate() {
        Log.d("AppA", "onCreate started")
        super.onCreate()
        _instance = this
        db = AppDatabase.getDatabase(this)
        Log.d("AppA", "onCreate completed")
    }

    companion object {
        private var _instance: AppA? = null
        val instance: AppA
            get() = _instance!!
    }
}