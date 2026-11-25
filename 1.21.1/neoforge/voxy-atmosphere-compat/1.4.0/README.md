# Voxy Atmosphere Compat 1.4.0 (NeoForge 1.21.1)

**Jar:** `voxy-atmosphere-compat-1.4.0.jar` in this folder. Same targets as [1.3.0](../1.3.0/README.md). Everything
from 1.3.0 is included; same `VoxyAtmoApi` (apiVersion 1).

## Fix: clouds cutting through nearby mountains
1.2.0 lifted the cloud layer to the 90th percentile of the terrain out to the cloud distance, plus 48 blocks. In
JJThunder To The Max the terrain near you can be far higher than that (tested: 90th percentile gave Y 1072 while
peaks within 2 km reached Y 1576). The cloud layer then sat inside those mountains. From above, especially at night
in a storm, that shows as dark, ragged cloud patches cut by terrain with streaks along the cliffs; from below as slabs.

1.4.0 also samples a 128-block grid within 2048 blocks of you and keeps the cloud base above the highest peak there
(plus the margin). The percentile still applies to the wider area. Tested in a JJThunder To The Max world:

| Position | Cloud height (1.3.0 rule) | Highest peak within 2048 | 1.4.0 cloud height |
|---|---|---|---|
| 6000, 0 | about 1072 | 1576 | 1632 |
| 0, 6000 | lower | 1268 | 1328 |
| 12000, 4000 | lower | 1507 | 1568 |
| -6000, -6000 (lowland) | 192 | 142 | 192 |

New option in `voxyatmocompat-client.toml`, section `cloud_look_and_coverage`:
`cloudClearRadiusBlocks` (default 2048, 0 = percentile only). The height is re-checked every time you move a quarter
of the cloud distance, so it changes as you travel between lowlands and mountain ranges.

## Build
`cd source && ./gradlew build` (NeoForge 21.1.219, ModDevGradle). Drop the bridged mods' jars into `source/libs`
first (see `libs/README.md`).
