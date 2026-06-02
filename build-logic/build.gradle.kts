plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
}

gradlePlugin {
    plugins {
        register("host-app") {
            id = "com.example.host-app"
            implementationClass = "com.example.buildlogic.HostAppPlugin"
        }
        register("plugin-api") {
            id = "com.example.plugin-api"
            implementationClass = "com.example.buildlogic.PluginApiPlugin"
        }
    }
}
