package com.example.buildlogic

import org.gradle.api.artifacts.ArtifactView
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

private val SANITIZE_REGEX = Regex("[^a-zA-Z0-9._-]")

fun derivePluginId(id: ComponentIdentifier): String =
    when (id) {
        is ProjectComponentIdentifier ->
            id.projectPath.removePrefix(":").replace(":", "-")

        is ModuleComponentIdentifier ->
            "${id.group}--${id.module}"

        else ->
            id.displayName.replace(SANITIZE_REGEX, "-")
    }

fun ResolvableDependencies.jarArtifactView(): ArtifactView =
    artifactView {
        attributes {
            attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ArtifactTypeDefinition.JAR_TYPE,
            )
        }
    }

fun hashFileContent(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        DigestInputStream(input, digest).use { dis ->
            val buffer = ByteArray(8192)
            @Suppress("ControlFlowWithEmptyBody")
            while (dis.read(buffer) != -1) {}
        }
    }
    return digest.toHexString()
}

fun hashFileCollection(files: Iterable<File>, rootDir: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    files.sortedBy { it.relativeTo(rootDir).path }
        .filter { it.isFile }
        .forEach { file ->
            digest.update(file.relativeTo(rootDir).path.toByteArray(Charsets.UTF_8))
            file.inputStream().buffered().use { input ->
                DigestInputStream(input, digest).use { dis ->
                    val buffer = ByteArray(8192)
                    @Suppress("ControlFlowWithEmptyBody")
                    while (dis.read(buffer) != -1) {}
                }
            }
        }
    return digest.toHexString()
}

private fun MessageDigest.toHexString(): String =
    digest().joinToString("") { "%02x".format(it) }
