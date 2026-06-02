package com.example.buildlogicimport org.gradle.api.provider.ListProperty

abstract class PluginApiExtension {

    abstract val additionalEntries: ListProperty<String>

    /**
     * Include package to shared mappings
     */
    fun includePackage(packageName: String) {
        additionalEntries.add(packageName)
    }

    /**
     * include class to shared mappings
     */
    fun includeClass(className: String) {
        additionalEntries.add(className)
    }
}
