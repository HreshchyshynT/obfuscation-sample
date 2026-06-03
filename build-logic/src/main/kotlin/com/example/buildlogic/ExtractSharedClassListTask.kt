package com.example.buildlogic


import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
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
        val mapping = pluginFileMapping.get()
        val invertedIndex = TreeMap<String, TreeSet<String>>()

        inputFiles.files.forEach { file ->
            val pluginId = mapping[file.absolutePath]
            if (pluginId == null) return@forEach
            val pluginEntries = TreeSet<String>()
            when(file.extension) {
                "aar" -> processAar(file, pluginEntries)
                "jar" -> processJar(file, pluginEntries)
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
        val pluginApisView = pluginApisConfig.incoming.jarArtifactView()
        val sdkView = sdkConfig.incoming.jarArtifactView()

        inputFiles.from(provider { pluginApisView.files })
        sdkFiles.from(provider { sdkView.files })

        val pluginFileMappingProvider = pluginApisView.artifacts.resolvedArtifacts.map {
            it.associate { artifact ->
                artifact.file.absolutePath to derivePluginId(artifact.id.componentIdentifier)
            }
        }
        classPluginIndexFile.set(
            project.layout.buildDirectory.file(
                "shared-mappings/${variant.name}/class-plugin-index.properties"
            )
        )
        pluginFileMapping.set(pluginFileMappingProvider)
    }
}
