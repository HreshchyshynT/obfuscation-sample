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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.obfuscation_sample_b.ui.theme.Obfuscation_sample_bTheme

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
                            Button(onClick = {
                                bootstrapReflection(
                                    targetPackage = "com.example.obfuscation_sample_a",
                                    className = "com.example.obfuscation_sample_a.db.DbDataManager",
                                    methodName = "foo"
                                )
                            }) {
                                Text("Trigger Reflection Bootstrap")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun bootstrapReflection(
        targetPackage: String,
        className: String,
        methodName: String,
    ) {
        try {
            Log.d("ReflectionBootstrap", "Starting bootstrap for $targetPackage")

            // 1. createPackageContext: Use the flags CONTEXT_INCLUDE_CODE and CONTEXT_IGNORE_SECURITY.
            val targetContext = createPackageContext(
                targetPackage,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )

            // 2. ClassLoader Access: Fetch the ClassLoader from that context.
            val classLoader = targetContext.classLoader

            // 3. Class Loading: Load the class by its obfuscated string (e.g., "a.b.c").
            val targetClass = classLoader.loadClass(className)

            Log.d("ReflectionBootstrap", "Class $className loaded successfully")

            // 4. Method Invocation: Use .getDeclaredMethod() and .invoke().
            val method = try {
                targetClass.getDeclaredMethod(methodName)
            } catch (e: NoSuchMethodException) {
                Log.e(
                    "ReflectionBootstrap",
                    "Method $methodName not found in $className. Available methods:"
                )
                targetClass.declaredMethods.forEach {
                    Log.d(
                        "ReflectionBootstrap",
                        "  - ${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})"
                    )
                }
                targetClass.declaredClasses.forEach {
                    Log.d(
                        "ReflectionBootstrap",
                        "class  - ${it.name}"
                    )
                }

                throw e
            }

            method.isAccessible = true

            // Invoke the method. If it's static, the first argument is null.
            val result = method.invoke(null)

            Log.d("ReflectionBootstrap", "Method $methodName invoked successfully. Result: $result")
        } catch (e: Exception) {
            Log.e("ReflectionBootstrap", "Error during reflection bootstrap", e)
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