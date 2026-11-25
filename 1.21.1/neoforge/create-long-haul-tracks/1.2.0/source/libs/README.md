# Compile-only jars

Drop these here before building. They are other authors' mods: never committed, never bundled.

| File | Where |
|------|-------|
| `create-1.21.1-6.0.10.jar` | Modrinth project `create` (LNytGWDc), version 6.0.10+mc1.21.1 |
| `flywheel-neoforge-1.21.1-1.0.6.jar`, `ponder-neoforge-1.0.82+mc1.21.1.jar` | `META-INF/jarjar/` inside the Create jar |
| `xaeroworldmap-neoforge-1.21.1-1.47.0.jar` | Modrinth project `xaeros-world-map` (NcUtCpym) |

Voxy Atmosphere Compat and JJThunder To The Max are not compile dependencies: both are detected at runtime.

Building in a sandbox that cannot reach `launchermeta.mojang.com`: seed
`~/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_version_manifest.json` with the 1.21.1 version JSON from
`piston-meta.mojang.com`.

In-world tests: copy the Create jar into `run-gametest/mods/` and run `./gradlew runGameTestServer`.
