# PMWeather Advanced Compat 1.0.0 (NeoForge 1.21.1)

Makes PMWeatherCM1 form and place storms across the whole area Voxy / Voxy SeedGen draws, with PMPHYSICS and
PMWeather Aeronautics compat reacting to those storms. **Not shader compatible**: Iris and Oculus are declared
incompatible and the game will refuse to start with them.

## Required setup

| Mod | Version | Notes |
|---|---|---|
| ProtoManly's Weather (`pmweather`) | **0.16.4** exactly | PMWeatherCM1 1.1.7 only accepts 0.16.4 |
| PMWeatherCM1 (`pmweathercm1`) | 1.1.7+ | plus its simulation pack in `config/pmweather/simulations/` (from the PMWeatherCM1 GitHub releases) |
| PMPHYSICS (`pmocean`) | 0.1.0 | optional, needs the fml.toml line below |
| PMWeather Aeronautics compat (`pmweather_aeronautics`) | 1.1 | optional, needs Sable 2.x and NeoForge 21.1.234+ |
| voxy-forged / Voxy SeedGen | any | optional, the storm area follows them |
| NeoForge | 21.1.234+ | |

**PMPHYSICS will crash the game at startup** next to PMWeather 0.16.4 ("Mod pmocean only supports pmweather 0.17 or
above"). PMPHYSICS only calls a PMWeather method that 0.16.4 also has, so tell NeoForge to drop that version check:
add this to `config/fml.toml` (NeoForge 21.1 has this feature built in):

```toml
[dependencyOverrides]
	pmocean = ["-pmweather"]
```

(Replace the line `dependencyOverrides = {}` if it is there.)

**Singleplayer only.** PMWeatherCM1 1.1.7 crashes a dedicated server at startup (one of its mixins loads client
classes) and its page lists LAN/multiplayer as unstable. This mod works in singleplayer and for a LAN host.

## What it does

- **Storm area = Voxy area.** Your client tells the server how far Voxy (and SeedGen) draws terrain:
  min(SeedGen reach, Voxy LOD distance), capped at 32000 blocks because CM1 does not draw simulated storms further
  than that. CM1's simulated storms normally form within a fixed 10000-block circle and PMWeather's own within
  `spawnrange` (max 2048); both now reach the storm area. CM1 still decides whether and which storm forms (its own
  scenario choice from the atmosphere at your position, its limit of two simulated storms); only where changes.
- **Storms still come to you.** `localSpawnShare` (default 0.35) of spawn checks keep the original ranges.
- **The nearest storm is the one drawn.** CM1 draws only one simulated supercell and one cyclone at a time, the
  first in the client's storm list (spawn order). The list is kept nearest-first, so a far storm never hides the one
  above you.
- **No lag spikes from far storms.** PMWeather generated the chunk under every far sample point on the server thread
  just to look for fires there (seconds per spawn check in JJThunder). Chunks that are not loaded yet are now treated as
  fire-free instead of generated or waited for (`avoidFarChunkLoads`).
- **PMPHYSICS and Aeronautics.** Both read PMWeather's wind (`WindEngine.getWind`), which CM1 drives, so CM1 storms
  raise waves and push Aeronautics/Sable creations with no extra bridge. Tested: next to a CM1 storm the wind both
  read rose from 8 mph to 12-74 mph (varies run to run), identically for both.
- `PMWeather maxStorms` is raised with the area (cap `maxStormsCap`, 10) for PMWeather's own storm types.
- `/pmwadv status` (client) shows the reported area, the installed stack and every storm with its distance.

What PMPHYSICS 0.1.0 itself does: wave height from wind, boat wakes, wave splashes (client visuals). Block damage
(tornadoes, debarking, etc.) comes from PMWeather itself and applies to CM1 storms as well.

## Config

`config/pmweatheradvcompat-common.toml`: `followVoxyDistance`, `fallbackAreaBlocks` (16384, used when no client
reported a distance), `maxAreaBlocks` (32000), `localSpawnShare` (0.35), `scaleMaxStorms`, `maxStormsCap`,
`avoidFarChunkLoads`.
`config/pmweatheradvcompat-client.toml`: `reportVoxyDistance`, `areaOverrideBlocks` (-1 = read Voxy),
`nearestCm1StormFirst`.

## Test (JJThunder world, headless client, full stack)

`xvfb-run ./gradlew runAutoTest` creates a world with JJThunder 0.6.0 terrain (height 2096), runs the GameTests on
the integrated server and quits. With a 24000-block area over 1500 spawn checks: CM1 simulated storms formed up to
22722 blocks away (CM1 alone: 10000), all within the area. Spawn check average 2.5 ms, worst 0.3 s (before the
chunk-load fixes: average 105 ms, single checks up to 31 s, because PMWeather generated chunks at far sample points).
Local/far split 34/66 for a 0.35 setting. All 4 tests pass.
The headless test cannot show CM1's rendering (software GL); that has to be checked in game.
