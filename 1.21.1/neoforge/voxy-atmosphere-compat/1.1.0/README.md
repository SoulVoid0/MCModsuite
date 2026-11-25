# Voxy Atmosphere Compat 1.1.0 (NeoForge 1.21.1)

**Jar:** `voxy-atmosphere-compat-1.1.0.jar` in this folder (superseded by [1.2.0](../1.2.0/README.md)). Built against NeoForge 21.1.219, voxy-forged 1.1.0,
Simple Clouds 0.7.3, Voxy SeedGen 0.3.1, Project Atmosphere 0.9.1.2. Everything from [1.0.0](../1.0.0/README.md)
is still included; this file lists what changed.

**Tested:** in a world (NeoForge 21.1.267, Sodium 0.8.13, FFAPI 0.116.15, voxy-forged 1.1.0, SeedGen 0.3.1,
Simple Clouds 0.7.3, Project Atmosphere 0.9.1.2) under a software renderer, with screenshots from y=100/200/330
in four directions, before and after. All mixins apply; the built-in resource pack loads.

## Fixes

### Cloud slab, hard edge, stripes and holes
Simple Clouds' cloud shader blends far clouds into **opaque** fog colour (from 1/4 of the cloud distance to all of
it). At Voxy/SeedGen distances that turns every far cloud into one flat fog-coloured slab, ending in a hard rounded
wall before the horizon; that wall showed stripes and holes against distant LOD terrain.
1.1.0 ships a built-in resource pack (`soft_cloud_edges`, always on, top priority) that overrides
`simpleclouds:clouds` and `simpleclouds:clouds_transparency` fragment shaders: far clouds now **fade to transparent**
over the last ~45% of the cloud distance, with only a light fog tint. No slab, no wall, no hard edge.

### Thin cloud coverage
Simple Clouds allows at most 5 cloud formations (8 in data packs) spread over a 10 000-block spawn radius, renders
at most 10, and Project Atmosphere only spawns formations within 5 000 blocks. So most of the visible sky at
Voxy/SeedGen distances was empty.
* Formation limits are scaled with the cloud distance (x3 up to 6 000 blocks, x4 up to 12 000, x6 beyond; config).
* Up to 64 formations are rendered (config); when there are more, the nearest are kept, in their original order.
* Project Atmosphere may spawn formations out to the cloud distance.

## Stable API (for other mods, e.g. the Create long-haul tracks mod)
`dev.soulvoid.voxyatmo.api.VoxyAtmoApi`, all static, never throw, signatures never change (new methods may be added):

| Method | Returns |
|---|---|
| `int apiVersion()` | 1 |
| `int distantTerrainBlocks()` | SeedGen reach, else Voxy LOD distance, in blocks; -1 if unknown |
| `int cloudDistanceBlocks()` | cloud distance this mod enforces; -1 if none |
| `int seedgenGenerationChunks()` | SeedGen generation distance in chunks; -1 if absent |
| `void requestDistance(String ownerId, int blocks)` | ask for clouds to reach at least `blocks` (largest request wins, capped by `maxCloudDistanceBlocks`) |
| `void clearDistance(String ownerId)` | drop that request |

Call it by reflection when this mod is optional. `TargetDistance.terrainBlocks()` from 1.0.0 is kept too.

## New config (`config/voxyatmocompat-client.toml`, section `cloud_look_and_coverage`)
| Key | Default | Meaning |
|---|---|---|
| `softCloudEdges` | true | the soft-edge resource pack (when false it is only listed, off, in the resource pack screen) |
| `moreCloudFormations` | true | scale formation limits and render cap |
| `formationScalePercent` | 0 | 0 = automatic, else the multiplier in % |
| `maxRenderedFormations` | 64 | formations rendered at once (Simple Clouds: 10) |
| `spawnReachFollowsClouds` | true | Project Atmosphere spawns out to the cloud distance |

## Building
```
cd source
# drop the compile-only jars into libs/ (see libs/README.md)
./gradlew build      # -> source/build/libs/voxy-atmosphere-compat-1.1.0.jar
```
