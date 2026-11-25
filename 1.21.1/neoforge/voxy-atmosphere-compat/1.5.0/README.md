# Voxy Atmosphere Compat 1.5.0 (NeoForge 1.21.1)

**Jar:** `voxy-atmosphere-compat-1.5.0.jar` in this folder. Same targets as [1.4.0](../1.4.0/README.md). Everything
from 1.4.0 is included; same `VoxyAtmoApi` (apiVersion 1).

## Fix: fragmented, see-through clouds with holes and slats (present since 1.0.0)
Since 1.0.0 the mod grows Simple Clouds' cloud area to reach Voxy SeedGen's distance by widening its outermost
(8x) level-of-detail ring. Simple Clouds splits a fixed 48 MB mesh buffer evenly across all mesh chunks, and
widening the ring multiplied the chunk count: 364 at Simple Clouds' HIGH preset, 1560 at a 32k-block SeedGen
distance, 5448 at 64k. Each chunk's share fell to a quarter or a fifteenth, so storm clouds overflowed it
(`Mesh status: Chunk overflow` in F3) and the faces that did not fit were dropped. That is the dark, fragmented,
see-through cloud ceiling with holes and slats.

1.5.0:
- keeps Simple Clouds' three LOD rings (its region shader cannot take more) and makes the outermost ring coarser
  (16x, 32x or 64x) instead of wider. At 64k blocks: 732 chunks instead of 5448. The 4x ring is widened so detail
  near you is the same or better than the HIGH preset.
- grows the mesh buffers with the chunk count (up to 2.5x, about 100 MB each at 64k), so no chunk gets less than
  Simple Clouds' own share.

Tested in a JJThunder To The Max world at a 64k-block SeedGen distance, during a storm, looking up from Y 1000:
1.4.0 reported chunk overflow on every check and showed broken clouds. 1.5.0 reported normal mesh status and
solid clouds, both without shaders and with Iris 1.8.14-beta.1 + Complementary Reimagined r5.10.

New option in `voxyatmocompat-client.toml`, section `distance`: `coarserRingsForFarClouds` (default true; false
restores the 1.4.0 behaviour for comparison). It replaces `allowCoarserLodRing`.

## Build
`cd source && ./gradlew build` (NeoForge 21.1.219, ModDevGradle). Drop the bridged mods' jars into `source/libs`
first (see `libs/README.md`).
