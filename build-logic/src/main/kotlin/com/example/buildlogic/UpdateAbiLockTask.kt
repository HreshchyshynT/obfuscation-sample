package com.example.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class UpdateAbiLockTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pendingLockFile: RegularFileProperty

    @get:OutputFile
    abstract val lockFile: RegularFileProperty

    @TaskAction
    fun update() {
        val source = pendingLockFile.get().asFile
        val target = lockFile.get().asFile
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        logger.lifecycle("ABI lock file updated: ${target.name}")
    }
}
