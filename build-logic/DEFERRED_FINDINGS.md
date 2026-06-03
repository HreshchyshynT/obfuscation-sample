# Deferred Build-Logic Findings

## 2.2 `afterEvaluate` Anti-Pattern in PluginApiPlugin (Medium)

**File**: `PluginApiPlugin.kt:40`

`afterEvaluate` is used to wire compile task outputs into `classesDirs`. This breaks configuration caching and creates ordering fragility. The compile outputs should be wired via `variant.artifacts` or task providers instead.

## 2.3 `includePackage` and `includeClass` Are Identical in PluginApiExtension (Low)

**File**: `PluginApiExtension.kt:13, 20`

Both methods call `additionalEntries.add(...)` with no semantic distinction. A package prefix and a fully-qualified class name are treated identically downstream. Either remove one or add validation (e.g., ensure packages end with `.`).

## 3.2 `buildSharedDepsVersions` Scans Wrong Configuration (Low/Correctness)

**File**: `HostAppPlugin.kt` — `buildSharedDepsVersions()`

Fetches the `"implementation"` configuration's dependencies. In Android, `implementation` is a bucket config that doesn't directly contain resolved metadata — actual resolved deps are in variant-specific configurations. This works only because Gradle populates the declared-dependency list eagerly, but it will miss transitive or BOM-managed versions. A proper solution would resolve against the variant's compile classpath.
