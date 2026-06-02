package com.example.buildlogic


import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.TreeMap
import java.util.TreeSet
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.zip.ZipFile
import kotlin.collections.component1
import kotlin.collections.component2

@CacheableTask
abstract class ExtractSharedClassListTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sdkFiles: ConfigurableFileCollection


    @get:Input
    abstract val pluginFileMapping: MapProperty<String, String>

    @get:OutputFile
    abstract val classPluginIndexFile: RegularFileProperty

    @TaskAction
    fun extract() {
        buildClassPluginIndex()
    }

    private fun buildClassPluginIndex() {
        val mapping = pluginFileMapping.get()
        val invertedIndex = TreeMap<String, TreeSet<String>>()

        inputFiles.files.forEach { file ->
            val pluginId = mapping[file.absolutePath]
            println("file: ${file.name} pluginId: $pluginId")
            if (pluginId == null) return@forEach
            val pluginEntries = TreeSet<String>()
            when {
                file.name.endsWith(".aar") -> processAar(file, pluginEntries)
                file.name.endsWith(".jar") -> processJar(file, pluginEntries)
            }
            pluginEntries.forEach { entry ->
                invertedIndex.getOrPut(entry) { TreeSet() }.add(pluginId)
            }
        }

        val allPluginIds = invertedIndex.values.flatMapTo(TreeSet()) { it }

        sdkFiles.files.forEach { file ->
            if (file.extension == "jar") {
                val sdkEntries = TreeSet<String>()
                scanJarClasses(file, sdkEntries)
                sdkEntries.forEach { entry ->
                    invertedIndex.getOrPut(entry) { TreeSet() }.addAll(allPluginIds)
                }
            }
        }

        val indexFile = classPluginIndexFile.get().asFile
        indexFile.parentFile.mkdirs()
        indexFile.writeText(buildString {
            invertedIndex.forEach { (entry, pluginIds) ->
                appendLine("$entry=${pluginIds.joinToString(",")}")
            }
        })

        logger.lifecycle(
            "Built class-plugin index with ${invertedIndex.size} entries " +
                    "across ${invertedIndex.values.flatten().toSet().size} plugins"
        )
    }

    private fun processAar(aarFile: File, entries: MutableSet<String>) {
        ZipFile(aarFile).use { zip ->
            val classesJarEntry = zip.getEntry("classes.jar") ?: return
            zip.getInputStream(classesJarEntry).buffered().use { inputStream ->
                scanJarForTxt(JarInputStream(inputStream), entries)
            }
        }
    }

    private fun processJar(jarFile: File, entries: MutableSet<String>) {
        jarFile.inputStream().buffered().use { inputStream ->
            scanJarForTxt(JarInputStream(inputStream), entries)
        }
    }

    private fun scanJarForTxt(jar: JarInputStream, entries: MutableSet<String>) {
        jar.use { stream ->
            var entry: JarEntry? = stream.nextJarEntry
            while (entry != null) {
                if (entry.name == GenerateSharedClassListTask.SHARED_MAPPINGS_CLASSES_FILE) {
                    val content = stream.readBytes().toString(Charsets.UTF_8)
                    content.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .forEach { entries.add(it) }
                    return
                }
                entry = stream.nextJarEntry
            }
        }
    }

    private fun scanJarClasses(jarFile: File, entries: MutableSet<String>) {
        jarFile.inputStream().buffered().use { inputStream ->
            JarInputStream(inputStream).use { jarStream ->
                var entry: JarEntry? = jarStream.nextJarEntry
                while (entry != null) {
                    if (!entry.isDirectory
                        && entry.name.endsWith(".class")
                        && entry.name != "module-info.class"
                        && !entry.name.startsWith("META-INF/")
                    ) {
                        val className = entry.name.removeSuffix(".class")
                            .replace("/", ".")
                        entries.add(className)
                    }
                    entry = jarStream.nextJarEntry
                }
            }

        }
    }

    fun Project.configureTask(
        variant: ApplicationVariant,
        sdkConfig: Configuration,
        pluginApisConfig: Configuration,
    ) {

        val pluginApisFilesProvider = provider {
            pluginApisConfig.incoming.artifactView {
                attributes {
                    attribute(
                        ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                        ArtifactTypeDefinition.JAR_TYPE,
                    )
                }
            }.files
        }

        val sdkFilesProvider = provider {
            sdkConfig.incoming.artifactView {
                attributes {
                    attribute(
                        ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                        ArtifactTypeDefinition.JAR_TYPE,
                    )
                }
            }.files
        }

        inputFiles.from(pluginApisFilesProvider)
        sdkFiles.from(sdkFilesProvider)

        val artifacts = pluginApisConfig.incoming.artifactView {
            attributes {
                attribute(
                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                    "android-classes-jar",
                )
            }
        }.artifacts
        val pluginFileMappingProvider = artifacts.resolvedArtifacts.map {
            it.associate { artifact ->
                val pluginId = when (val id = artifact.id.componentIdentifier) {
                    is ProjectComponentIdentifier ->
                        id.projectPath.removePrefix(":").replace(":", "-")

                    is ModuleComponentIdentifier ->
                        "${id.group}--${id.module}"

                    else ->
                        id.displayName.replace(Regex("[^a-zA-Z0-9._-]"), "-")
                }
                println("pluginId: $pluginId")
                artifact.file.absolutePath to pluginId

            }
        }
        classPluginIndexFile.set(
            project.layout.buildDirectory.file(
                "shared-mappings/class-plugin-index.properties"
            )
        )
        pluginFileMapping.set(pluginFileMappingProvider)

    }
}
