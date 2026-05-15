package com.example.obfuscation_sample_b

import android.content.Context
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
import com.example.shared_module.Shared
import dalvik.system.DexClassLoader
import dalvik.system.PathClassLoader
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
                                        className = "com.example.obfuscation_sample_a.SharedImpl",
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
        try {
            Log.d("ReflectionBootstrap", "Starting bootstrap for $targetPackage")
            val otherAppContext = createPackageContext(
                targetPackage,
                CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY,
            )

            val apkInfo = otherAppContext.applicationInfo

            val apkSources = apkInfo.sourceDir
            val dexLoader = DexClassLoader(
                apkSources,
                null,
                null,
                otherAppContext.classLoader,
            )

//            val apkInfo = packageManager.getApplicationInfo(targetPackage, 0)
//            val apkSources = apkInfo.sourceDir
//            val dexLoader = DexClassLoader(
//                apkSources,
//                null,
//                null,
//                classLoader,
//            )


            // load Class<?>
            val entryPointClass = dexLoader.loadClass(className)

//            val classInstance = entryPointClass.getDeclaredConstructor().newInstance() as Shared
//            entryPointClass.declaredMethods.forEach { method ->
//                Log.d(
//                    "ReflectionBootstrap",
//                    "method: ${method.name}, args: ${method.parameterTypes.map { "${it.typeName}" }}"
//                )
//            }

            Log.d(
                "ReflectionBootstrap",
                "superclass: ${entryPointClass.superclass}, genericInterfaces: ${entryPointClass.genericInterfaces.firstOrNull()?.typeName}"
            )
            val instance = entryPointClass.getDeclaredConstructor().newInstance()
            Log.d(
                "ReflectionBootstrap",
                "instance: $instance is shared ${instance is Shared}"
            )
        } catch (e: Exception) {
            Log.e("ReflectionBootstrap", "Error during reflection bootstrap", e)
            Log.e("ReflectionBootstrap", "Error during reflection bootstrap cause", e.cause)
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