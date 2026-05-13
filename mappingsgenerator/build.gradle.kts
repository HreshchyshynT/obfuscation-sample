plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.mappingsgenerator"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.mappingsgenerator"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
}

tasks.register("generateSharedMapping") {
    // 1. Ensure R8 runs first
    dependsOn("minifyReleaseWithR8")

    group = "mapping"
    description = "Extracts coroutine mappings from R8 output"

    doLast {
        val mappingFile = file("${layout.buildDirectory.get()}/outputs/mapping/release/mapping.txt")
        // Choose a stable location where your plugins can find it
        val outputFile = rootProject.file("shared-metadata/coroutines.map")

        if (mappingFile.exists()) {
            outputFile.parentFile.mkdirs()

            val result = StringBuilder()
            var insideTargetPackage = false

            mappingFile.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    // Check if the line is a class mapping for coroutines
                    if (!line.startsWith(" ")) {
                        insideTargetPackage = line.startsWith("kotlinx.coroutines") ||
                                line.startsWith("kotlin.coroutines")
                    }

                    // If we are inside a target class, or it's a member of that class, keep it
                    if (insideTargetPackage) {
                        result.append(line).append("\n")
                    }
                }
            }

            outputFile.writeText(result.toString())
            println("Successfully exported mappings to: ${outputFile.absolutePath}")
        } else {
            throw GradleException("Mapping file not found! Ensure 'minifyEnabled' is true in release build.")
        }
    }
}