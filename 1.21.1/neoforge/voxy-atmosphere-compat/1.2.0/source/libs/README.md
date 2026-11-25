# Compile-only jars

Drop these here before building. They are other authors' mods: never committed, never bundled.

| File | What | Where |
|------|------|-------|
| `voxy.jar` | voxy-forged 1.1.0 (NeoForge 1.21.1) | build https://github.com/falling-colud/voxy-forged (`./gradlew build`), copy `build/libs/voxy-1.1.0.jar` |
| `simpleclouds.jar` | Simple Clouds 0.7.3 for NeoForge 1.21.1 | CurseForge project 1121215, file 6928979 (`simpleclouds-0.7.3+1.21.1-all.jar`) |
| `simplecloudsapi-neoforge-1.4-1.21.1.jar`, `crackerslib-neoforge-1.21.1-0.4.6.jar` | Simple Clouds API and CrackersLib | extract from `META-INF/jarjar/` inside the Simple Clouds jar |

Project Atmosphere and Voxy SeedGen are not compile dependencies: they are detected at runtime.

Building voxy-forged in a sandbox that cannot reach `launchermeta.mojang.com`: NeoForm Runtime reads the version
manifest from its cache first, so seed `~/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_version_manifest.json`
with the 1.21.1 version JSON from `piston-meta.mojang.com`.
