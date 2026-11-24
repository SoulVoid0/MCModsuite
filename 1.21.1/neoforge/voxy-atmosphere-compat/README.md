# Voxy Atmosphere Compat (NeoForge 1.21.1)

Compatibility patch for **voxy-forged** ([falling-colud/voxy-forged](https://github.com/falling-colud/voxy-forged)),
**Voxy SeedGen**, **Simple Clouds** and **Project Atmosphere** (with its required deps: Gabou's Libs, Better Days,
a season mod). Client side. Every patch only activates when the mods it bridges are installed.

> **Status: source complete, jar not built yet.** The build sandbox used to write this could not reach the NeoForge
> maven, Mojang, CurseForge or Modrinth. The jar goes in this folder once it is built (see *Building*).

## What it fixes

### 1. Clouds above and below distant (LOD) terrain
Without shaders Simple Clouds uses its `DEFAULT` pipeline: clouds are drawn in `afterSky`, before terrain, and their
depth is copied into the main depth buffer. voxy-forged builds its LOD stencil from that buffer
(`AbstractRenderPipeline.initDepthStencil` marks every pixel with depth != 1.0 as near vanilla terrain and skips LOD
there). So every cloud pixel is mistaken for near terrain:

* LOD mountains in front of a cloud get cut out and the cloud shows through them;
* from above the cloud layer, the LOD terrain under the clouds is never drawn where clouds cover it.

The patch forces Simple Clouds' `SHADER_SUPPORT` pipeline: clouds draw after the level, copy the main depth (which
now holds Voxy's LOD depth, blitted with the vanilla projection) and depth-test against it. Clouds nearer than LOD
terrain draw over it, clouds behind it are hidden, whether the camera is above or below them. Iris already uses that
pipeline; Distant Horizons' own pipeline is left alone. Side effects of the switch are handled:

* the high 2D atmospheric cloud layer (only drawn by `DEFAULT`) is restored;
* the cloud pass is moved before precipitation so rain/snow (vanilla or Project Atmosphere's) is not painted over;
* Project Atmosphere's tornado/hurricane rendering hooks inside `ShaderSupportPipeline.afterLevel` keep running.

### 2. Clouds reach Voxy SeedGen's generation distance
* The cloud render distance (`ModifyCloudRenderDistanceEvent`, which sets Simple Clouds' cloud fog range) follows
  **Voxy SeedGen's generation distance**, or Voxy's LOD distance when SeedGen is not installed.
* Simple Clouds' geometry stops at its cloud-area radius (HIGH preset about 10240 blocks). When the target is further,
  the outermost LOD ring is widened (or, opt-in, a coarser ring is added) and the mesh generator is rebuilt when the
  distance changes. `getDepthFar` already follows that radius, so the far plane grows with it.
* The shader fog is stretched during the cloud pass so far clouds fade over their own distance instead of turning
  into flat fog colour at the terrain fog edge.
* Project Atmosphere spawns its cloud regions over a ~19 km radius (`DEFAULT_REGION_RADIUS` 2400 cloud units x 8),
  so weather fills the extended area; its clouds are rendered by Simple Clouds, which this patch drives.

### 3. Voxy SeedGen with voxy-forged
* voxy-forged's built-in distant generator stands down while SeedGen is installed (it reuses voxy-forged's own exit
  for external pregenerators, `DistantGenerationManager.isGroundworkPresent`). SeedGen is meant to replace Voxy's
  worldgen; running both generates and ingests the same area twice.
* Voxy's LOD render distance is raised at runtime to cover SeedGen's generation distance, so SeedGen never fills an
  area Voxy does not draw.
* SeedGen's distance is read from its config file under `config/` (any file with "seedgen" in the name), or set it in
  this mod's config.

## Config (`config/voxyatmocompat-client.toml`)
| Key | Default | Meaning |
|-----|---------|---------|
| `clouds_vs_lod.cloudsDepthTestAgainstLod` | true | fix 1 |
| `clouds_vs_lod.restoreAtmosphericLayer` | true | keep the 2D sky cloud layer |
| `clouds_vs_lod.rainInFrontOfClouds` | true | draw clouds before precipitation |
| `distance.cloudsFollowGenerationDistance` | true | fix 2 |
| `distance.distancePercent` | 100 | cloud distance as % of the terrain distance |
| `distance.seedgenDistanceChunksOverride` | 0 | 0 = read SeedGen's config |
| `distance.maxCloudDistanceBlocks` | 32768 | performance cap |
| `distance.growCloudArea` | true | grow Simple Clouds' cloud area |
| `distance.allowCoarserLodRing` | false | add a coarser ring instead of widening the last one |
| `distance.fogFollowsClouds` / `cloudFogPercent` | true / 90 | cloud fog range |
| `seedgen.standDownVoxyDistantGeneration` | true | fix 3 |
| `seedgen.raiseVoxyRenderDistance` | true | fix 3 |

`/voxyatmo status` prints what is installed and the distances in force.

## Works with
* Make it Compatible: Voxy: its duplicate Simple Clouds patches win and ours stand down (no double redirects).
* Better Simple Clouds: no overlap (it caps LOD detail; this patch only grows the area).

## Building
```
cd source
# drop voxy.jar (voxy-forged 1.1.0) and simpleclouds.jar into libs/ (see libs/README.md)
./gradlew build      # -> source/build/libs/voxy-atmosphere-compat-1.0.0.jar
```

## Credits
The Simple Clouds pipeline switch, atmospheric-layer restore, rain ordering and fog stretch are adapted from
[Make it Compatible: Voxy](https://github.com/falling-colud/make-it-compatible-voxy) (MIT, leon.raineri).
