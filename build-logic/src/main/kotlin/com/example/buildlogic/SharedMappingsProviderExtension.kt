package com.example.buildlogic

import org.gradle.api.provider.ListProperty

abstract class SharedMappingsProviderExtension {

    abstract val additionalEntries: ListProperty<String>

    fun includePackage(packageName: String) {
        additionalEntries.add(packageName)
    }

    fun includeClass(className: String) {
        additionalEntries.add(className)
    }
}
