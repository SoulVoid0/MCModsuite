# Voxy Atmosphere Compat 1.2.0 (NeoForge 1.21.1)

**Jar:** `voxy-atmosphere-compat-1.2.0.jar` in this folder (superseded by [1.3.0](../1.3.0/README.md)). Same targets as [1.1.0](../1.1.0/README.md)
(NeoForge 21.1.219, voxy-forged 1.1.0, Simple Clouds 0.7.3, Voxy SeedGen 0.3.1, Project Atmosphere 0.9.1.2).
Everything from 1.1.0 is included; same `VoxyAtmoApi` (apiVersion 1).

## Fix: clouds inside the terrain (stripes and slabs over Voxy/SeedGen terrain)
Simple Clouds puts its cloud layer at a fixed Y (128 by default). In tall worlds such as JJThunder To The Max, and
anywhere distant LOD terrain is high, that layer sits inside the mountains. From a peak you then only see clouds in
the valleys between ridges, which looks like rows of stripes and cut-off slabs over Voxy/SeedGen terrain. 1.1.0's
extra cloud formations made this show up more.

1.2.0 lifts the cloud layer above the terrain:
* Every 5 s it samples the world generator's surface height (so terrain that is not loaded, i.e. what Voxy/SeedGen
  show, counts) at 97 points around you, out to the cloud distance. It repeats when you move a quarter of that.
* The cloud height becomes the 90th percentile of those heights plus 48 blocks (rounded to 16). The highest peaks
  still reach into the clouds.
* It never goes below the world's own cloud height (remembered per dimension). If you or another mod set the height
  with a command, that becomes the new base.
* Single player and LAN host (the mod runs client side; on a dedicated server set `/clouds height set`).

Tested headless: the height is raised and synced (`cloud height 128 -> 368` with test settings), and it is left alone
on vanilla-height terrain with default settings.

## New config (`cloud_look_and_coverage`)
| Key | Default | Meaning |
|---|---|---|
| `autoCloudHeight` | true | lift clouds above the terrain |
| `cloudHeightTerrainPercentile` | 90 | 100 = above every sampled point |
| `cloudHeightMarginBlocks` | 48 | gap between that terrain height and the cloud base |
