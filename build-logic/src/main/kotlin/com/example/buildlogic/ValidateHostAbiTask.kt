package com.example.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

@CacheableTask
abstract class ValidateHostAbiTask : DefaultTask() {

    @get:Input
    abstract val sharedDepsVersions: MapProperty<String, String>

    @get:Input
    abstract val pluginVersions: MapProperty<String, String>

    @get:Input
    abstract val sdkVersions: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sdkSourceFiles: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:Internal
    abstract val lockFile: RegularFileProperty

    @get:OutputFile
    abstract val resultFile: RegularFileProperty

    @get:OutputFile
    abstract val pendingLockFile: RegularFileProperty

    @TaskAction
    fun validate() {
        val lock = readLockFile(lockFile.get().asFile)

        val currentSdkVersions = buildEffectiveSdkVersions()
        val changedSdks = findChangedEntries(lock.sdks, currentSdkVersions)

        val currentSharedDeps = sharedDepsVersions.get()
        val changedSharedDeps = findChangedEntries(lock.sharedDeps, currentSharedDeps)

        val currentPlugins = pluginVersions.get()
        val changedPlugins = mutableListOf<String>()
        val newPlugins = mutableListOf<String>()
        for ((pluginId, currentVersion) in currentPlugins) {
            val previousVersion = lock.plugins[pluginId]
            if (previousVersion == null) {
                newPlugins.add(pluginId)
            } else if (previousVersion != currentVersion) {
                changedPlugins.add(pluginId)
            }
        }

        if (changedSharedDeps.isNotEmpty() || changedSdks.isNotEmpty()) {
            val parts = mutableListOf<String>()
            if (changedSdks.isNotEmpty()) {
                parts.add("SDK modules: ${changedSdks.joinToString(", ")}")
            }
            if (changedSharedDeps.isNotEmpty()) {
                parts.add("shared dependencies: ${changedSharedDeps.joinToString(", ")}")
            }
            logger.warn(
                "ABI change detected in ${parts.joinToString(" and ")}. " +
                    "Plugin developers should rebuild their APIs with the updated dependencies."
            )
        }

        val result = AbiValidationResult(
            changedSdks = changedSdks,
            changedSharedDeps = changedSharedDeps,
            changedPlugins = changedPlugins,
            newPlugins = newPlugins,
        )

        writeLockFile(
            pendingLockFile.get().asFile,
            LockFileData(
                sharedDeps = currentSharedDeps,
                sdks = currentSdkVersions,
                plugins = currentPlugins,
            ),
        )

        writeResultFile(resultFile.get().asFile, result)

        logger.lifecycle(
            "ABI validation complete: " +
                "sdks=${changedSdks.size} changed, " +
                "sharedDeps=${changedSharedDeps.size} changed, " +
                "plugins=${changedPlugins.size} changed, " +
                "${newPlugins.size} new"
        )
    }

    private fun buildEffectiveSdkVersions(): Map<String, String> {
        val declared = sdkVersions.get().toMutableMap()
        val rootDir = rootDirectory.get().asFile
        val sourceHash = hashFileCollection(sdkSourceFiles.files, rootDir)
        for ((sdkId, value) in declared) {
            if (value == NEEDS_SOURCE_HASH) {
                declared[sdkId] = "sha256:$sourceHash"
            }
        }
        return declared
    }

    private fun findChangedEntries(
        previous: Map<String, String>,
        current: Map<String, String>,
    ): List<String> {
        val changed = mutableListOf<String>()
        for ((key, currentValue) in current) {
            val previousValue = previous[key]
            if (previousValue != null && previousValue != currentValue) {
                changed.add(key)
            }
        }
        return changed
    }

    companion object {
        const val NEEDS_SOURCE_HASH = "__needs_source_hash__"
    }
}

data class AbiValidationResult(
    val changedSdks: List<String>,
    val changedSharedDeps: List<String>,
    val changedPlugins: List<String>,
    val newPlugins: List<String>,
) {
    fun hasAnyChange(): Boolean =
        changedSdks.isNotEmpty() || changedSharedDeps.isNotEmpty() ||
            changedPlugins.isNotEmpty() || newPlugins.isNotEmpty()

    fun hasCommonChange(): Boolean =
        changedSdks.isNotEmpty() || changedSharedDeps.isNotEmpty()

    companion object {
        fun fromFile(file: File): AbiValidationResult {
            if (!file.exists()) {
                return AbiValidationResult(emptyList(), emptyList(), emptyList(), emptyList())
            }
            return readResultFile(file)
        }
    }
}

data class LockFileData(
    val sharedDeps: Map<String, String> = emptyMap(),
    val sdks: Map<String, String> = emptyMap(),
    val plugins: Map<String, String> = emptyMap(),
)

private fun readLockFile(file: File): LockFileData {
    if (!file.exists() || file.readText().isBlank()) return LockFileData()
    return parseLockJson(file.readText())
}

private fun writeLockFile(file: File, data: LockFileData) {
    file.parentFile?.mkdirs()
    file.writeText(toLockJson(data))
}

private fun writeResultFile(file: File, result: AbiValidationResult) {
    file.parentFile?.mkdirs()
    file.writeText(toResultJson(result))
}

private fun readResultFile(file: File): AbiValidationResult {
    return parseResultJson(file.readText())
}

private fun parseLockJson(json: String): LockFileData {
    val sections = parseJsonObject(json)
    return LockFileData(
        sharedDeps = parseJsonStringMap(sections["sharedDeps"] ?: "{}"),
        sdks = parseJsonStringMap(sections["sdks"] ?: "{}"),
        plugins = parseJsonStringMap(sections["plugins"] ?: "{}"),
    )
}

private fun toLockJson(data: LockFileData): String {
    return buildString {
        appendLine("{")
        appendLine("  \"sharedDeps\": ${toJsonStringMap(data.sharedDeps)},")
        appendLine("  \"sdks\": ${toJsonStringMap(data.sdks)},")
        appendLine("  \"plugins\": ${toJsonStringMap(data.plugins)}")
        appendLine("}")
    }
}

private fun toResultJson(result: AbiValidationResult): String {
    return buildString {
        appendLine("{")
        appendLine("  \"changedSdks\": ${toJsonStringList(result.changedSdks)},")
        appendLine("  \"changedSharedDeps\": ${toJsonStringList(result.changedSharedDeps)},")
        appendLine("  \"changedPlugins\": ${toJsonStringList(result.changedPlugins)},")
        appendLine("  \"newPlugins\": ${toJsonStringList(result.newPlugins)}")
        appendLine("}")
    }
}

private fun parseResultJson(json: String): AbiValidationResult {
    val fields = parseJsonObject(json)
    return AbiValidationResult(
        changedSdks = parseJsonStringList(fields["changedSdks"] ?: "[]"),
        changedSharedDeps = parseJsonStringList(fields["changedSharedDeps"] ?: "[]"),
        changedPlugins = parseJsonStringList(fields["changedPlugins"] ?: "[]"),
        newPlugins = parseJsonStringList(fields["newPlugins"] ?: "[]"),
    )
}

private fun parseJsonObject(json: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val trimmed = json.trim().removeSurrounding("{", "}")
    var i = 0
    while (i < trimmed.length) {
        val keyStart = trimmed.indexOf('"', i)
        if (keyStart == -1) break
        val keyEnd = trimmed.indexOf('"', keyStart + 1)
        val key = trimmed.substring(keyStart + 1, keyEnd)
        val colonIdx = trimmed.indexOf(':', keyEnd + 1)
        val valueStart = firstNonWhitespace(trimmed, colonIdx + 1)
        val value: String
        when (trimmed[valueStart]) {
            '{' -> {
                val end = findMatchingBrace(trimmed, valueStart, '{', '}')
                value = trimmed.substring(valueStart, end + 1)
                i = end + 1
            }
            '[' -> {
                val end = findMatchingBrace(trimmed, valueStart, '[', ']')
                value = trimmed.substring(valueStart, end + 1)
                i = end + 1
            }
            '"' -> {
                val end = trimmed.indexOf('"', valueStart + 1)
                value = trimmed.substring(valueStart, end + 1)
                i = end + 1
            }
            else -> {
                var end = valueStart
                while (end < trimmed.length && trimmed[end] != ',' && trimmed[end] != '}') end++
                value = trimmed.substring(valueStart, end).trim()
                i = end
            }
        }
        result[key] = value
        i++
    }
    return result
}

private fun parseJsonStringMap(json: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val trimmed = json.trim().removeSurrounding("{", "}")
    if (trimmed.isBlank()) return result
    val regex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"")
    for (match in regex.findAll(trimmed)) {
        result[match.groupValues[1]] = match.groupValues[2]
    }
    return result
}

private fun parseJsonStringList(json: String): List<String> {
    val trimmed = json.trim().removeSurrounding("[", "]")
    if (trimmed.isBlank()) return emptyList()
    return Regex("\"([^\"]+)\"").findAll(trimmed).map { it.groupValues[1] }.toList()
}

private fun toJsonStringMap(map: Map<String, String>): String {
    if (map.isEmpty()) return "{}"
    return map.entries.joinToString(", ", "{", "}") { (k, v) ->
        "\"${escapeJson(k)}\": \"${escapeJson(v)}\""
    }
}

private fun toJsonStringList(list: List<String>): String {
    if (list.isEmpty()) return "[]"
    return list.joinToString(", ", "[", "]") { "\"${escapeJson(it)}\"" }
}

private fun escapeJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

private fun firstNonWhitespace(s: String, from: Int): Int {
    var i = from
    while (i < s.length && s[i].isWhitespace()) i++
    return i
}

private fun findMatchingBrace(s: String, start: Int, open: Char, close: Char): Int {
    var depth = 0
    var inString = false
    for (i in start until s.length) {
        val c = s[i]
        if (c == '"' && (i == 0 || s[i - 1] != '\\')) {
            inString = !inString
        } else if (!inString) {
            if (c == open) depth++
            else if (c == close) {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return s.length - 1
}
