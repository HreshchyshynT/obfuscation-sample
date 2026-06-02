# Compatibility Manifest for Host-Plugin ABI Tracking

## Context

The host app loads plugins at runtime via DexClassLoader. Both share a common SDK (`shared_module`) via `implementation` and share runtime-provided dependencies (coroutines, etc.) via `compileOnly` in the plugin. Three things can break compatibility:

1. **SDK changes** — `shared_module` API changes, plugins must rebuild
2. **Plugin API changes** — plugin's own API changed
3. **Shared `compileOnly` dependency version drift** — plugin was compiled against coroutines X, host provides coroutines Y at runtime — R8 mappings become stale

### Key insight about dependency visibility

- SDK (`shared_module`) is `implementation` in the plugin — appears in POM — host CAN resolve the version from the plugin's transitive deps
- Coroutines and other runtime-provided deps are `compileOnly` in the plugin — NOT in POM — host CANNOT see them — **provider must stamp these versions**

### Solution

Provider plugin generates `META-INF/shared-mappings-compat.properties` bundled in the AAR. It records versions of `compileOnly` shared dependencies that are invisible to the host. The host extracts the manifest, checks SDK version from dependency resolution, checks `compileOnly` dep versions from the manifest.

---

## Plugin Developer Perspective

```kotlin
// plugin-api/build.gradle.kts
plugins {
    id("com.android.library")
    id("com.example.shared-mappings-provider")
}

dependencies {
    implementation(project(":shared_module"))  // SDK — host sees this via resolution
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")  // host provides runtime — invisible to host
}

sharedMappingsProvider {
    // Declare compileOnly deps that host provides at runtime.
    // The provider plugin resolves the actual version from the compile classpath automatically.
    sharedDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    // Existing DSL (unchanged)
    includePackage("com.thirdparty.shared.models")
    includeClass("com.thirdparty.SomeSpecificClass")
}
```

### What happens on build

1. Existing: `generateReleaseSharedClassList` generates `META-INF/shared-mappings-classes.txt`
2. New: `generateReleaseCompatManifest` generates `META-INF/shared-mappings-compat.properties`
3. Both bundled into AAR's `classes.jar` via resources source set

### Generated manifest content

```properties
# shared-mappings-compat.properties
dep.org.jetbrains.kotlinx\:kotlinx-coroutines-core=1.7.3
```

Versions are resolved automatically — the plugin developer only declares `group:artifact`, the task resolves the actual version from `releaseCompileClasspath`.

---

## Host Developer Perspective

```kotlin
// host/build.gradle.kts
plugins {
    id("com.example.shared-mappings")
}

dependencies {
    implementation(project(":shared_module"))
}

sharedMappings {
    plugin(project(":shared_module"))
    sdk("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
```

### What happens on `assembleRelease`

1. Existing: `extractSharedClassList` reads class lists from plugin AARs
2. New: `checkPluginCompatibility` — for each plugin:
   - Extract `shared-mappings-compat.properties` from the plugin's AAR
   - SDK version check: resolve the plugin's transitive deps from `sharedMappingsClasspath`, find `shared_module`, compare version against host's own `shared_module` version
   - Dep version check: read `dep.*` entries from manifest, compare against host's resolved versions for matching `sdk()` entries
   - Emit per-plugin report

### Compatibility outcomes

| Check | Match | Action |
|---|---|---|
| No manifest in plugin | — | WARNING: plugin doesn't provide compat info, skip check |
| SDK version matches | Host and plugin use same shared_module version | OK |
| SDK version mismatch | Plugin built against different shared_module | WARNING: plugin may be incompatible |
| `compileOnly` dep versions match | Same coroutines version | OK — R8 mappings are valid |
| `compileOnly` dep versions mismatch | Different coroutines version | WARNING: R8 mappings may be stale, regenerate |

---

## New Files

### `build-logic/.../GenerateCompatManifestTask.kt`

`@CacheableTask` — generates the compat properties file on the provider side.

```
Inputs:
  @Input sharedDependencyCoordinates: ListProperty<String>  — "group:artifact" list from extension
  @Input resolvedVersions: MapProperty<String, String>      — resolved "group:artifact" -> "version" map
  @OutputDirectory outputDir: DirectoryProperty

Task action:
  1. For each declared sharedDependency coordinate, look up resolved version
  2. Write META-INF/shared-mappings-compat.properties
```

### `build-logic/.../CheckPluginCompatibilityTask.kt`

`@CacheableTask` — compares plugin manifests against host state.

```
Inputs:
  @InputFiles pluginArtifacts: ConfigurableFileCollection    — resolved plugin AARs/JARs (same source as extractSharedClassList)
  @Input hostSharedDepVersions: MapProperty<String, String>  — host's resolved versions for sdk() entries
  @OutputFile reportFile: RegularFileProperty

Task action:
  1. For each plugin artifact (AAR/JAR):
     a. Extract shared-mappings-compat.properties (same JAR scanning pattern as ExtractSharedClassListTask)
     b. Read dep.* entries
     c. Compare each dep version against hostSharedDepVersions
  2. Write report file with per-plugin results
  3. Log warnings for mismatches
```

---

## Modified Files

### `SharedMappingsProviderExtension.kt`

Add:
- `internal abstract val sharedDependencies: ListProperty<String>`
- `fun sharedDependency(coordinate: String)` — adds `group:artifact` to the list

### `SharedMappingsProviderPlugin.kt`

Add per variant:
- Resolve `sharedDependencies` coordinates against the variant's compile classpath to get versions
- Register `GenerateCompatManifestTask` with resolved versions
- Wire output into resources source set (same pattern as `GenerateSharedClassListTask`)

### `SharedMappingsPlugin.kt`

Add:
- Resolve host's `sdk()` dependency versions from `sharedMappingsSdkClasspath`
- Register `CheckPluginCompatibilityTask`
- Wire into `assembleRelease`

### `SharedMappingsExtension.kt`

No changes needed — `sdk()` already exists and provides the host's shared dep declarations.

---

## Task Graph

```
Plugin developer build:
  compileReleaseKotlin
    -> generateReleaseSharedClassList (existing)
    -> generateReleaseCompatManifest (new)
    -> assembleRelease -> AAR with both META-INF files

Host developer build:
  assembleRelease
    +-- extractSharedClassList (existing)
    +-- checkPluginCompatibility (new)
    +-- ...
```

---

## Verification

1. `./gradlew :build-logic:build` — compiles
2. Add `sharedDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core")` to `shared_module`'s provider config
3. `./gradlew :shared_module:assembleRelease` — verify `shared-mappings-compat.properties` inside AAR
4. `./gradlew :host:assembleRelease` — verify check task runs and reports OK
5. Change coroutines version in host's `sdk()` — verify WARNING about version mismatch
