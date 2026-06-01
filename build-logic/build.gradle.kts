plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
}

gradlePlugin {
    plugins {
        register("sharedMappings") {
            id = "com.example.shared-mappings"
            implementationClass = "com.example.buildlogic.SharedMappingsPlugin"
        }
    }
}
