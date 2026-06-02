package com.example.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest

abstract class ValidateHostAbiTask : DefaultTask() {

    // 1. Task Inputs
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sdkSourceFiles: ConfigurableFileCollection

    @get:Input
    abstract val dependenciesVersions: Property<String>

    // Pass rootDir safely as a DirectoryProperty to avoid calling project.rootDir at execution time
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    // 2. Lock file lives outside the build dir (in shared-metadata/), read at execution time
    @get:Internal
    abstract val lockFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sharedMappingsFile: RegularFileProperty

    @get:OutputFile
    abstract val abiChangedFlagFile: BoolFlagFile

    @TaskAction
    fun validate() {
        val currentHash = calculateCurrentHash()
        val existingHash = getExistingHash()
        val isChanged = existingHash != currentHash
        val mappingFileExists = sharedMappingsFile.get().asFile.exists()

        if (isChanged) lockFile.get().asFile.writeText(currentHash)

        // Persist the boolean flag to a file
        abiChangedFlagFile.write(!mappingFileExists || isChanged)

        logger.lifecycle("Plugin ABI Validation complete. abiChanged = $isChanged, shared mapping file exists = $mappingFileExists")
    }

    private fun getExistingHash(): String {
        val targetLockFile = lockFile.get().asFile
        if (targetLockFile.exists()) {
            val existingHash = targetLockFile.readText().trim()
            logger.lifecycle("existing hash: $existingHash")
            return existingHash
        } else {
            logger.lifecycle("Lock file does not exist at ${targetLockFile.absolutePath}. Marking ABI as changed.")
            return ""
        }
    }

    private fun calculateCurrentHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val rootDir = rootDirectory.get().asFile
        sdkSourceFiles.files.sortedBy { it.relativeTo(rootDir).path }
            .filter { it.isFile }
            .forEach { file ->
                digest.update(file.relativeTo(rootDir).path.toByteArray(Charsets.UTF_8))
                digest.update(file.readBytes())
            }

        digest.update(dependenciesVersions.get().toByteArray(Charsets.UTF_8))

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

typealias BoolFlagFile = RegularFileProperty

fun BoolFlagFile.write(value: Boolean) {
    get().asFile.writeText(value.toString())
}

fun BoolFlagFile.read(): Boolean {
    val file = get().asFile
    return file.exists() && file.readText().toBoolean()
}
