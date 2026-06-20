# CLAUDE.md

Guidance for working in this repository. Standalone; `AGENTS.md` is a shorter sibling note and is left as-is.

## What this project is

This is a fork of **Fabric Loader** — a mod loader from the Minecraft community — repurposed to load
mods for **Spiral Knights**, a rolling-release, heavily obfuscated game distributed through Three Rings'
**Getdown** updater.

Fabric was already designed to be modular: all game-specific logic lives behind the `GameProvider`
interface, so `minecraft/` and `spiralknights/` are sibling modules. Nearly all of this fork's work
lives in the **`spiralknights/`** module, with small touches to shared code (`src/`) and the Gradle build.

- Loader version: `0.19.3`, group `net.fabricmc`.
- Modules (`settings.gradle`): root `fabric-loader`, `:minecraft`, `:spiralknights`, `:junit`, `:minecraft:minecraft-test`.
- The game lives at (on macOS, installed through Steam): `/Users/<current_user>/Library/Application Support/Steam/steamapps/common/Spiral Knights`, with a bundled JVM at `java_vm/bin`.

## The inverted remapping model (the conceptual heart)

Spiral Knights is obfuscated and re-obfuscated on **every rolling release**, so Fabric's normal strategy
does not apply. The direction of remapping is **inverted**:

| | Stock Fabric (Minecraft) | This fork (Spiral Knights) |
| --- | --- | --- |
| What gets remapped | the **game** | the **mods** |
| Direction | official → intermediary | **intermediary → official** |
| Result | mods run against stable intermediary names | the obfuscated game is left untouched; mods are remapped to match it |

This is driven entirely by `GameProvider` overrides in
[`SpiralKnightsGameProvider.java`](spiralknights/src/main/java/net/fabricmc/loader/impl/game/spiralknights/SpiralKnightsGameProvider.java):

- `getRuntimeNamespace() → "official"`
- `getDefaultModDistributionNamespace() → "intermediary"`
- `requiresRuntimeModRemap() → true`
- `getRuntimeModRemapClasspath()` exposes the game jars + valid parent classpath, so the remapper can resolve game types while remapping.

The actual remap is performed by the **shared, stock-Fabric** machinery
[`RuntimeModRemapper`](src/main/java/net/fabricmc/loader/impl/discovery/RuntimeModRemapper.java), using
bundled **tiny-remapper** (with a mapping provider built `intermediary → official`), **mapping-io**, and
**class-tweaker**. The fork doesn't reimplement remapping — it just points the existing pipeline the other
direction via the namespace overrides above. Namespace constants `OFFICIAL_NAMESPACE` /
`INTERMEDIARY_NAMESPACE` live in
[`MappingConfiguration.java`](src/main/java/net/fabricmc/loader/impl/launch/MappingConfiguration.java).

## Mapping resolution

Mappings are resolved per game version by
[`SpiralKnightsMappingResolver.resolve(appDir, version)`](spiralknights/src/main/java/net/fabricmc/loader/impl/game/spiralknights/SpiralKnightsMappingResolver.java), in this order:

1. `fabric.mappingPath` system property → used as-is.
2. `fabric.spiralknights.mappingPath` → local override file.
3. Cache hit at `<appDir>/.fabric/mappings/spiralknights/intermediary/<version>.tiny` (unless a refresh is requested).
4. Download from a URL template — default `https://raw.githubusercontent.com/below-haven/mapping-chain/refs/heads/main/intermediary/%s.tiny` (the `below-haven/mapping-chain` repo), then write into the cache.

Resolved mappings are validated to contain **both** `official` and `intermediary` namespaces. Because the
game is re-obfuscated each release, every `version` has its own `.tiny` file — hence the per-version cache.

## Launch flow & Getdown parsing

Launch is a **two-phase bootstrap**: a first JVM parses the Getdown config and re-spawns the game's own JVM
with Fabric wired in.

### Phase A — bootstrap

Entry point [`SpiralKnightsLauncher.main`](spiralknights/src/main/java/net/fabricmc/loader/impl/game/spiralknights/SpiralKnightsLauncher.java).
Requires `-Dfabric.spiralknights.appDir=<SK install>`. It reads `getdown.txt`, then `buildCommand()`
assembles and spawns a fresh JVM:

- uses the game's **bundled JVM** `<appDir>/java_vm/bin/java` (resolved by `GetdownUtil.resolveJavaBinaryPath`),
- re-applies Getdown's `jvmarg`s (macro-expanded),
- spoofs `-Dcom.threerings.getdown=true` (so the game believes Getdown launched it),
- sets `-Dfabric.spiralknights.bootstrapped=true` (so the child doesn't bootstrap again),
- runs `net.fabricmc.loader.impl.launch.knot.KnotClient` with the original args, cwd = appDir, inherited IO.

### Phase B — Fabric / Knot

On re-entry, `bootstrapped` is set, so `SpiralKnightsLauncher` delegates straight to `KnotClient.main`.
`SpiralKnightsGameProvider` (registered via
[`META-INF/services/net.fabricmc.loader.impl.game.GameProvider`](spiralknights/src/main/resources/META-INF/services/net.fabricmc.loader.impl.game.GameProvider))
then runs `locateGame → initialize → unlockClassPath → launch`:

- **`locateGame`**: parse getdown; pick the game version and entrypoint (`com.threerings.projectx.client.ProjectXApp`); resolve the code jars; find the jar that contains the entrypoint; resolve mappings; capture launch args.
- **`initialize`**: set `user.dir` to appDir; restrict parent classpath visibility; apply `app.*` system properties; warn if not running on the bundled JVM; **hard-fail if not bootstrapped**; register entrypoint patches.
- **`unlockClassPath`**: add the resolved game jars to Knot's target classloader.
- **`launch`**: load the entrypoint via the Knot classloader and invoke its static `main` through a `MethodHandle`.

### Getdown config parsing

[`GetdownConfig.read(appDir)`](spiralknights/src/main/java/net/fabricmc/loader/impl/game/spiralknights/getdown/GetdownConfig.java:46)
reads `<appDir>/getdown.txt` (`key = value` lines, `#` comments). Keys consumed:

- `version` — game version (drives mapping/version lookup).
- `class` — main class / entrypoint.
- `code` (repeatable) — classpath jars (paths under `code/`).
- `jvmarg` (repeatable) — JVM arguments forwarded to the spawned JVM.
- `ui.mac_dock_icon` — turned into `-Xdock:icon=…` on macOS only.

Values may carry an `[os]` qualifier (`[windows]` / `[mac os x]` / `[linux]`), filtered via
`QualifiedValue` + `CurrentOs`; macros (`%APPDIR%`, `%VERSION%`) are expanded by `GetdownUtil`. The parser
validates the presence of a version, a main class, and at least one `code` entry. Supporting classes live
in `spiralknights/.../getdown/`: `GetdownUtil`, `QualifiedValue`, `CurrentOs`.

## SK-specific runtime patch (console logging)

The game redirects stdout to a log file via `ToolUtil.configureLog("projectx.log")`.
[`ConsoleLogMirrorPatch`](spiralknights/src/main/java/net/fabricmc/loader/impl/game/spiralknights/patch/ConsoleLogMirrorPatch.java)
injects a call (at the entrypoint, during `initialize`) to install
[`ConsoleLogMirrorHook`](spiralknights/src/main/java/net/fabricmc/loader/impl/game/spiralknights/hook/ConsoleLogMirrorHook.java),
which mirrors that output back to the console. This is the canonical example of how the fork patches game
bytecode at the entrypoint — add similar patches the same way.

## Java 25 compatibility (zipfs + javax embedding)

Spiral Knights ships a **stripped** Java 25 runtime that is missing:

- `java.compiler` (`javax.annotation.processing`, `javax.lang.model`, `javax.tools`) — needed by **Mixin**.
- full `jdk.zipfs` — needed for jar `FileSystem` operations during runtime mod remapping.

[`gradle/java25-compat.gradle`](gradle/java25-compat.gradle) uses a JDK 25 toolchain's `jimage` to extract
those classes from `lib/modules` and bundle them into the loader jar. The zipfs provider
(`jdk.nio.zipfs.ZipFileSystemProvider`) is registered as a **classpath `ServiceLoader` provider**
(`META-INF/services/java.nio.file.spi.FileSystemProvider`) rather than a JPMS module, and
[`FileSystemUtil`](src/main/java/net/fabricmc/loader/impl/util/FileSystemUtil.java) falls back to it. This
is the work behind the recent "embed javax", "extract zipfs", and "improve zipfs packaging" commits.

## System properties reference

Defined in [`SystemProperties.java:37`](src/main/java/net/fabricmc/loader/impl/util/SystemProperties.java):

| Property | Purpose |
| --- | --- |
| `fabric.spiralknights.appDir` | **Required.** Spiral Knights install directory (contains `getdown.txt`, `java_vm/`, `code/`). |
| `fabric.spiralknights.bootstrapped` | Internal flag set by the bootstrap to prevent re-bootstrapping; don't set by hand. |
| `fabric.spiralknights.logLevel` | Override the JUL log level for SK logging. |
| `fabric.spiralknights.mappingPath` | Local mapping file override (resolution step 2). |
| `fabric.spiralknights.mappingUrlTemplate` | Override the mapping download URL template (`%s` = version). |
| `fabric.spiralknights.disableMappingDownload` | Disable mapping download; rely on cache/override only. |
| `fabric.spiralknights.refreshMappings` | Force re-download even if a cached mapping exists. |
| `fabric.skipSpiralKnightsProvider` | Skip the SK `GameProvider` (lets another provider take over). |
| `fabric.mappingPath` | Generic Fabric mapping override (resolution step 1). |
| `fabric.gameVersion` | Override the detected game version. |
| `fabric.gameJarPath` / `fabric.gameJarPath.client` | Override the game jar lookup. |

## Build & run

> **TODO — unverified.** The two commands below are inferred from the build files and entrypoint code, not
> confirmed by running them. Update this section once verified.

**Build requirements** (from `README.md`): Gradle must run on **Java 21+**, but a full **JDK 25** (with
`bin/jimage` and `lib/modules`) must be resolvable — auto-downloaded via Foojay when online, or pointed at
with `org.gradle.java.installations.paths` offline.

- **Build (inferred):** `./gradlew build` — produces a shadowed fat jar. *TODO: confirm exact task / artifact name.*
- **Run (inferred):**
  ```sh
  "<appDir>/java_vm/bin/java" \
    -Dfabric.spiralknights.appDir="/Users/<current_user>/Library/Application Support/Steam/steamapps/common/Spiral Knights" \
    -cp <loader-fat-jar> \
    net.fabricmc.loader.impl.game.spiralknights.SpiralKnightsLauncher
  ```
  *TODO: confirm the real invocation — exact jar name, whether `SpiralKnightsLauncher` or a wrapper script is the entrypoint, and how the mods directory is configured.*

## Key files map

**Spiral Knights module** (`spiralknights/src/main/java/net/fabricmc/loader/impl/game/spiralknights/`):

| File | Role |
| --- | --- |
| `SpiralKnightsGameProvider.java` | The `GameProvider`: namespace overrides, game location, init, launch. |
| `SpiralKnightsLauncher.java` | Two-phase bootstrap entry point. |
| `SpiralKnightsMappingResolver.java` | Per-version mapping resolution (override → cache → download). |
| `getdown/GetdownConfig.java` (+ `GetdownUtil`, `QualifiedValue`, `CurrentOs`) | Getdown `getdown.txt` parsing. |
| `patch/ConsoleLogMirrorPatch.java` + `hook/ConsoleLogMirrorHook.java` | Example entrypoint bytecode patch (console log mirroring). |
| `resources/META-INF/services/net.fabricmc.loader.impl.game.GameProvider` | Registers the SK provider. |

**Shared loader** (`src/main/java/net/fabricmc/loader/impl/`):

| File | Role |
| --- | --- |
| `discovery/RuntimeModRemapper.java` | Runs the actual intermediary→official mod remap. |
| `launch/MappingConfiguration.java` | Namespace constants + runtime mapping config. |
| `util/SystemProperties.java` | All system-property keys. |
| `util/FileSystemUtil.java` | Jar `FileSystem` access, with bundled-zipfs fallback. |

**Build:** [`gradle/java25-compat.gradle`](gradle/java25-compat.gradle) — extracts/bundles Java 25 `java.compiler` + `jdk.zipfs`.
