# Compile-only jars

Drop these here before building. They are other authors' mods (All Rights Reserved): never committed, never bundled.

| File | What | Where |
|------|------|-------|
| `pmweather-0.16.4-1.21.1-alpha.jar` | ProtoManly's Weather 0.16.4 | https://cdn.modrinth.com/data/TfCsmTLI/versions/QIG999g7/pmweather-0.16.4-1.21.1-alpha.jar |
| `pmweathercm1-1.1.7.jar` | PMWeatherCM1 1.1.7 | https://cdn.modrinth.com/data/93e7Da23/versions/WLWN6G7u/pmweathercm1-1.1.7.jar |

PMPHYSICS and PMWeather Aeronautics compat are not compile dependencies: nothing here calls them, they read
PMWeather's wind themselves.

For `runGameTestServer`, put the same jars (plus any others under test) in `run-gametest/mods/`.

Building in a sandbox that cannot reach `launchermeta.mojang.com`: seed
`~/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_version_manifest.json` with the 1.21.1 version JSON
from `piston-meta.mojang.com`.
