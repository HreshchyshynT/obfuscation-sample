# Host-Side Mapping Generation Flow

## Overview

The host app is the single source of truth for R8 obfuscation mappings. It generates mappings for all shared code (plugin API modules + common dependencies), stores a version footprint, and produces per-plugin mapping files that plugins consume via `-applymapping`.

## Architecture

```
Host App
  |
  |-- plugin1-api module (dependency)
  |-- plugin2-api module (dependency)
  |-- shared dependencies (coroutines, common-utils)
  |
  |-- [R8 build] --> mapping.txt (full)
  |                   |
  |                   |--> host-mappings.map (coroutines + common-utils + all APIs)
  |                   |--> plugin1-api.map   (coroutines + common-utils + plugin1-api)
  |                   |--> plugin2-api.map   (coroutines + common-utils + plugin2-api)
  |
  |-- version-footprint.properties (tracks what was built)
```

## Development Flow

### Step 1: Define Plugin API Module

Create a library module that defines the contract between host and plugin.

```
plugin1-api/
  build.gradle.kts
  src/main/kotlin/com/example/plugin1/api/
```

Dependencies:
- `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:X.Y.Z")`
- `implementation("com.example:common-utils:X.Y.Z")`

### Step 2: Host Declares Plugin API Modules

Host adds each plugin API module as a dependency and registers it in the shared mappings DSL:

```kotlin
// host/build.gradle.kts
sharedMappings {
    plugin(project(":plugin1-api"))
    plugin(project(":plugin2-api"))
    sdk("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    sdk("com.example:common-utils:2.1.0")
}
```

### Step 3: Host Builds and Generates Mappings

On `assembleRelease`, the host:

1. Runs R8 obfuscation on the full app (including all API modules and shared deps)
2. Captures `mapping.txt`
3. Extracts relevant mapping entries per module/dependency
4. Produces:
   - **`host-mappings.map`** — full mappings the host uses via `-applymapping` on subsequent builds
   - **`plugin1-api.map`** — subset: coroutines + common-utils + plugin1-api classes
   - **`plugin2-api.map`** — subset: coroutines + common-utils + plugin2-api classes
5. Records **version footprint** — a snapshot of dependency versions used in this mapping generation

### Step 4: Plugin Consumes Mappings

Each plugin project applies its corresponding mapping file via `-applymapping` during its own R8 pass. This ensures the plugin's obfuscated names align with the host's.

```
Plugin 1 build:
  R8 with -applymapping plugin1-api.map
  --> Plugin's coroutines classes match host's obfuscation
  --> Plugin's common-utils classes match host's obfuscation
  --> Plugin's API classes match host's obfuscation
```

## Version Footprint

The host records which versions were used to generate current mappings:

```properties
# version-footprint.properties
org.jetbrains.kotlinx\:kotlinx-coroutines-core=1.7.3
com.example\:common-utils=2.1.0
plugin1-api.version=1.0.0
plugin2-api.version=1.0.0
```

This footprint drives regeneration decisions.

## Regeneration Rules

### Rule 1: Shared Dependency Version Change

**Trigger**: coroutines or common-utils version changes (any version change, not just major/minor).

**Action**: Regenerate mappings for ALL plugins that depend on the changed library.

**Rationale**: R8 mappings depend on the full class structure. Even patch-level changes can alter class/method layout, which changes R8's obfuscation decisions. Skipping regeneration risks `-applymapping` failures or silent name mismatches at runtime.

```
coroutines 1.7.3 --> 1.7.4
  --> regenerate plugin1-api.map (uses coroutines)
  --> regenerate plugin2-api.map (uses coroutines)
  --> regenerate host-mappings.map
```

### Rule 2: Plugin API Contract Update

**Trigger**: A specific plugin API module's version or content changes.

**Action**: Regenerate mappings ONLY for that specific plugin API module. Other plugins are unaffected.

```
plugin1-api 1.0.0 --> 1.1.0
  --> regenerate plugin1-api.map
  --> plugin2-api.map unchanged (different API, same shared deps)
  --> regenerate host-mappings.map (includes updated plugin1-api)
```

### Rule 3: New Plugin Arrival

**Trigger**: A new plugin API module is added as a host dependency.

**Action**: Generate mappings ONLY for the new API module. Existing plugins keep their current mappings.

```
plugin3-api added
  --> generate plugin3-api.map (coroutines + common-utils + plugin3-api)
  --> plugin1-api.map unchanged
  --> plugin2-api.map unchanged
  --> regenerate host-mappings.map (now includes plugin3-api)
```

### Rule 4: No Change

**Trigger**: No dependency version changes, no API module changes.

**Action**: Skip mapping generation entirely (Gradle cache up-to-date).

## Mapping File Structure

### Host Mappings (host-mappings.map)

Contains all mappings — every shared dependency + every API module. The host applies this on every release build to maintain stable obfuscation.

### Per-Plugin Mappings (pluginN-api.map)

Contains only:
- Shared dependency mappings (coroutines, common-utils) — same across all plugin files
- That plugin's API module mappings — unique per plugin

This means:
- `plugin1-api.map` and `plugin2-api.map` share identical coroutines + common-utils sections
- They differ only in their API-specific sections
- A plugin never receives mappings for another plugin's API

## Splitting Strategy: Class-List-Based Filtering

Uses the class/package lists that each plugin API module already provides via `SharedMappingsProviderPlugin` (`META-INF/shared-mappings-classes.txt`).

### Provider Side (existing)

Each API module declares what it needs:

```kotlin
sharedMappingsProvider {
    includePackage("kotlinx.coroutines")       // shared dep
    includePackage("com.example.utils")         // shared dep
    includePackage("com.example.plugin1.api")   // own API classes
}
```

`GenerateSharedClassListTask` writes these into `META-INF/shared-mappings-classes.txt`, bundled in the AAR.

### Host Side: Per-Module Extraction

`ExtractSharedClassListTask` produces **two outputs**:

1. **Merged class list** — all classes from all plugins combined. Used by the host for R8 keep rules.
2. **Per-plugin class lists** — one file per API module, preserving the source boundary.

```
build/shared-mappings/
  shared-class-list.txt              # all classes merged (host R8 input)
  per-plugin/
    plugin1-api-class-list.txt       # plugin1's classes only
    plugin2-api-class-list.txt       # plugin2's classes only
```

Each per-plugin list includes both shared dep classes (coroutines, common-utils) and the plugin's own API classes, because the provider declares all of them.

### Host Side: Mapping Filtering

After R8 produces `mapping.txt`, a new task filters it per plugin:

1. Parse `mapping.txt` line by line (class-level entries start at column 0, member entries are indented)
2. For each class entry, check if the fully qualified class name appears in the plugin's class list
3. If it matches, include that class and all its member mappings in the plugin's `.map` file
4. Package entries in the class list match as prefixes (e.g., `kotlinx.coroutines` matches `kotlinx.coroutines.Job`, `kotlinx.coroutines.flow.Flow`, etc.)

Output:

```
build/shared-mappings/
  host-mappings.map                  # full mappings for host -applymapping
  per-plugin/
    plugin1-api.map                  # coroutines + common-utils + plugin1-api
    plugin2-api.map                  # coroutines + common-utils + plugin2-api
```

## Dependency Graph for Regeneration

```
coroutines (shared)
  |
  +--> plugin1-api.map
  +--> plugin2-api.map
  +--> plugin3-api.map

common-utils (shared)
  |
  +--> plugin1-api.map
  +--> plugin2-api.map
  +--> plugin3-api.map

plugin1-api (isolated)
  |
  +--> plugin1-api.map only

plugin2-api (isolated)
  |
  +--> plugin2-api.map only
```

When a shared dependency changes, all downstream mapping files are invalidated.
When an isolated API module changes, only its mapping file is invalidated.

## Version Sensitivity Policy

**Default**: Regenerate on ANY version change, including patch-level updates.

**Rationale for default**:
1. R8 obfuscation depends on full class structure, not just public API
2. Patch versions can add/remove internal classes, change method counts, alter inheritance
3. R8's naming algorithm is deterministic but sensitive to input order and structure
4. The cost of regeneration is low compared to the cost of a runtime ClassNotFoundException

**Configurable**: The version comparison strategy should be pluggable. Possible policies:
- `ALL` (default) — any version change triggers regeneration
- `MINOR_AND_MAJOR` — skip patch-level bumps (e.g., 1.7.3 → 1.7.4 is ignored, 1.7 → 1.8 triggers)
- `MAJOR_ONLY` — only major version bumps trigger regeneration
- Per-dependency overrides — allow marking specific dependencies as "patch-safe"

This can be exposed via the DSL later if needed.
