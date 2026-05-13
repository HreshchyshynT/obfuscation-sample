package com.example.obfuscation_sample_b

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import com.example.obfuscation_sample_b.ui.theme.Obfuscation_sample_bTheme
import dalvik.system.DexClassLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.Continuation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Obfuscation_sample_bTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .padding(innerPadding)
                                .align(Alignment.Center),
                        ) {
                            Greeting(name = "App B (Bootstrap)")
                            val cs = rememberCoroutineScope()
                            Button(onClick = {
                                cs.launch {
                                    bootstrapReflection(
                                        targetPackage = "com.example.obfuscation_sample_a",
                                        className = "com.example.obfuscation_sample_a.db.DbDataManager",
                                        methodName = "foo"
                                    )
                                }
                            }) {
                                Text("Trigger Reflection Bootstrap")
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun bootstrapReflection(
        targetPackage: String,
        className: String,
        methodName: String,
    ) {
        wakeUp(targetPackage)
        delay(1000)
        try {
            Log.d("ReflectionBootstrap", "Starting bootstrap for $targetPackage")

            val apkInfo = packageManager.getApplicationInfo(
                targetPackage,
                0
            )

            val apkSources = apkInfo.sourceDir
            val dexLoader = DexClassLoader(
                apkSources,
                null,
                null,
                classLoader,
            )


            val entryPointClass = dexLoader.loadClass(className)

            val classInstance = entryPointClass.getDeclaredConstructor().newInstance()
            entryPointClass.declaredMethods.forEach { method ->
                Log.d(
                    "ReflectionBootstrap",
                    "method: ${method.name}, args: ${method.parameterTypes.joinToString(",")}"
                )
            }
            val method = entryPointClass.getDeclaredMethod("getItems", Continuation::class.java)
            method.parameters.forEach { p ->
                Log.d(
                    "ReflectionBootstrap",
                    "parameter: ${p.name}, type: ${p.type} is continuation: ${
                        p.type.isAssignableFrom(
                            Continuation::class.java
                        )
                    }"
                )
            }

            Log.d(
                "ReflectionBootstrap",
                "EntryPointClass: $entryPointClass, instance: $classInstance"
            )
        } catch (e: Exception) {
            Log.e("ReflectionBootstrap", "Error during reflection bootstrap", e)
        }

    }

    fun wakeUp(targetPackage: String) {
        val uri = "content://$targetPackage.provider".toUri()
        try {
            contentResolver.query(
                uri,
                null,
                null,
                null,
                null,
            )?.close()
            Log.d("WAKE_UP", "Success to query provider")
        } catch (e: Exception) {
            Log.e("WAKE_UP", "Failed to query provider", e)
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}