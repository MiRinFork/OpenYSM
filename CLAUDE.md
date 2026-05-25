# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

OpenYSM is an open-source replacement for the "Yes Steve Model" Minecraft mod (`2.6.5 Forge` baseline, currently `mod_version = 2.6.6`). It targets Minecraft 1.20.1 on both Fabric and Forge from a single codebase, using Architectury as the multi-loader abstraction. The mod replaces the vanilla player model with Bedrock-style models/animations via a vendored GeckoLib, plus an optional C++ native library (`ysm-core`) for fast rendering.

Native source lives in a separate repo: [OpenYSMDev/openysm.cpp](https://github.com/OpenYSMDev/openysm.cpp).

## Build commands

```bash
./gradlew build                # builds both platforms
./gradlew :fabric:build        # Fabric only
./gradlew :forge:build         # Forge only
./gradlew :forge:runClient     # standard Loom dev client (Forge)
./gradlew :fabric:runClient    # standard Loom dev client (Fabric)
```

Artifacts land in `<platform>/build/libs/openysm-<platform>-<version>.jar`. The archive base name is `openysm`, but `rootProject.name = 'yes_steve_model'` — directory/Gradle name and artifact name intentionally differ.

`mod_version` is the single source of truth in root `gradle.properties`; `processResources` expands `${version}` into `forge/.../mods.toml` and `fabric/.../fabric.mod.json`. The GitHub Actions workflow (`.github/workflows/build-release.yml`) uses JDK 21 to invoke Gradle but targets Java 17 bytecode; on push to `main` it auto-cuts a `v<mod_version>` tag and uploads both jars if that tag doesn't already exist.

## Module layout (Architectury)

- **`common/`** — platform-agnostic code, the bulk of the mod. Pulls in `fabric-loader` only for the `@Environment` annotation; do NOT use any other Fabric class here (it gets remapped on Forge).
- **`forge/`** — Forge entry (`@Mod` class `YesSteveModelForge`), Forge capability registration, most mod-compat impls.
- **`fabric/`** — Fabric entry (`YesSteveModelFabric` / `YesSteveModelFabricClient`), Cardinal Components wiring (`YsmComponents`), Fabric compat impls.
- **`libs/`** — flat-dir jars for the long list of mod-compat dependencies (Curios, Create, Iron's Spellbooks, etc.); they are NOT fetched from Maven. The `flatDir { dirs rootProject.file('libs') }` repository in root `build.gradle` resolves entries like `:elytraslot-forge:6.4.4+1.20.1`.

## Platform abstraction pattern

The codebase uses Architectury's `@ExpectPlatform`: declare a `static` method in `common/` annotated `@ExpectPlatform` that `throw new AssertionError()`. Provide an implementation class with the same fully-qualified path, plus the platform name as the package suffix, named `<Original>Impl`:

```
common: rip.ysm.api.PlatformAPI.isServer()
forge:  rip.ysm.api.forge.PlatformAPIImpl.isServer()
fabric: rip.ysm.api.fabric.PlatformAPIImpl.isServer()
```

The plugin rewrites bytecode at build time to redirect the common call site to the active platform's impl. Every mod-compat module under `rip.ysm.compat.*` follows this exact pattern (`CuriosCompat` → `curios/forge/CuriosCompatImpl` + `curios/fabric/CuriosCompatImpl`).

## Entry flow

1. Forge: `YesSteveModelForge` constructor (`@Mod`) → `EventBuses.registerModEventBus` → `YesSteveModel.init()`. Capabilities register in `onRegisterCapabilities` listener on the mod event bus.
2. Fabric: `YesSteveModelFabric#onInitialize` → `YesSteveModel.init()`. Client setup runs from `YesSteveModelFabricClient#onInitializeClient`. Cardinal Components register via the `cardinal-components-entity` entrypoint in `fabric.mod.json` (→ `YsmComponents`).
3. Shared: `YesSteveModel.init()` loads the native lib via `NativeLibLoader`, registers configs (only if native loaded), then `YsmEventBootstrap.register()` wires all common + (client-only) client events.

## Native library (`ysm-core`)

`com.elfmcys.yesstevemodel.NativeLibLoader` extracts the platform-specific binary from `/natives/<platform>/` in classpath resources to a per-OS storage dir and loads it via JNA:

- Windows: `%TEMP%/ysm/ysm-core.dll`
- Linux: `~/.ysm/libysm-core.so`
- macOS: `~/.ysm/libysm-core.{dylib}`
- Android: `$MOD_ANDROID_RUNTIME/libysm-core.so` (env var required)

Override the entire path with `YSM_CORE_LIB`. Prebuilt binaries are committed under `common/src/main/resources/natives/<platform>/`. The `compileNative` Gradle task that would build them via CMake is currently gated off (`if (true) return false` at the top).

Most of the mod no-ops when the native lib didn't load: `YesSteveModel.isAvailable()` gates config registration, capability registration on Forge, and the `LifecycleEvent.SETUP` handler in `CommonEvent`. When touching code that depends on native calls, mirror this gating.

## Mixins

Three mixin config files, each with its own root in resources:

- `common/src/main/resources/yes_steve_model.mixins.json` — common mixins; uses `MixinTweaker` as `IMixinConfigPlugin` (currently a no-op stub that just returns defaults)
- `forge/src/main/resources/yes_steve_model_forge.mixins.json`
- `fabric/src/main/resources/yes_steve_model_fabric.mixins.json`

Common mixins are split: client-only mixins live in `mixin/client/` and go in the `client:` array of the JSON; server/common mixins live at `mixin/` root and go in the `mixins:` array. Forge loads both files via `loom.forge.mixinConfig` declarations in `forge/build.gradle`; Fabric loads them through the `mixins:` array in `fabric.mod.json`.

## Networking

Custom channel: `yes_steve_model:2_6_0` (the protocol version `NetworkHandler.VERSION` is encoded into the channel resource path with dots replaced by underscores — bump both together). The client handshake state is tracked on the Netty `Channel` via an `AttributeKey<String>`, accessed through mixin accessors (`ConnectionAccessor#ysm$getChannel`, `ServerCommonPacketListenerImplAccessor#ysm$getConnection`). Packet classes live under `com.elfmcys.yesstevemodel.network.message`.

## Capabilities / Components

Same conceptual data on both loaders, different mechanisms:

- **Forge:** capability classes under `com.elfmcys.yesstevemodel.capability` registered in `YesSteveModelForge.onRegisterCapabilities` (subscribes to `RegisterCapabilitiesEvent` on the mod bus). Server-only ones are always registered; client-only ones (`PlayerCapability`, `ProjectileCapability`, `VehicleCapability`) skipped on dedicated server.
- **Fabric:** Cardinal Components. The component IDs (e.g. `yes_steve_model:star_models`) are declared in `fabric.mod.json` under `custom.cardinal-components`, and `YsmComponents` is the entrypoint that registers them with the entity component factory.

When adding a new capability/component, update **both** sides plus the Fabric `fabric.mod.json` ID list.

## Vendored GeckoLib

Code under `com.elfmcys.yesstevemodel.geckolib3` is a heavily-modified vendored copy of GeckoLib, NOT a dependency. The published GeckoLib jar is listed only as `modCompileOnly` for Forge (for reading signatures of types the runtime never sees). Treat this package as first-party code.

## Misc gotchas

- `@Keep` annotation in `com.elfmcys.yesstevemodel.util.obfuscate` marks methods/fields that must survive obfuscation (Mixin plugin entry points, Mod-required methods). Add it when introducing reflective entry points.
- Forge bundles ImageStream via `forgeRuntimeLibrary` + `include` (JIJ); Fabric uses `implementation` + `include`. Both `common` and `forge` declare ImageStream as `compileOnly`/`forgeRuntimeLibrary` — the actual classes only exist at runtime through the platform jars.
- `processResources` in both platform modules pulls common resources via `from(project(':common').sourceSets.main.resources)` with `DuplicatesStrategy.INCLUDE` — platform-specific overrides should live in the platform's own resources tree, not in `common`.
- Old config migration: `YesSteveModel.initConfig` renames the legacy `yes_steve_model-common.toml` to `yes_steve_model-client.toml` on first run. Don't break this path.
