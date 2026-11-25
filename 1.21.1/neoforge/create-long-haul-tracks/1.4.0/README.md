# Create: Long Haul Tracks 1.4.0 (NeoForge 1.21.1)

Plan and build Create train lines over very long distances. Either end can be far outside the loaded or rendered area.

Jar: `create-long-haul-tracks-1.4.0.jar`. Source: `source/`. Replaces 1.0.0 to 1.3.0 (install one version only).
Routes planned or half built with older versions keep working.

## Changes from 1.3.0

- **Huge climbs plan instead of failing as "Too steep".** When the route search runs out of steps (for example
  1,116 blocks up a JJThunder mountain over 2,400 blocks), the planner no longer falls back to a straight line that
  is too steep. It lays out **switchbacks**: rows across the line joined by horseshoe curves (four 45 degree
  turns), long enough to climb the height at the grade, next to the start or the end and on whichever side is
  cheaper over the terrain. The heights follow the ground as closely as the grade allows, so they run on the
  mountainside where they can and tunnel or bridge where they must. Rows never cross each other or the rest of
  the line.
- For climbs much longer than the distance, the search now makes two quick greedy passes before switching to
  switchbacks, instead of three long ones, so such routes plan sooner.
- Tested against the real JJThunder generator: a 1,387-block climb from a valley (y 519) to a peak (y 1906) about
  2,400 blocks away, where the search fails, plans as a 13.6 km switchback line in about 50 s; a 1,116-block climb
  over 2,400 blocks on JJThunder-scale test terrain also plans.

## Changes in 1.3.0

- Planning no longer stalls at 89% on JJThunder (density-probe terrain sampler, about 20x faster; the search no
  longer gets stuck below summits).
- Surveying and building no longer stall at 1% (no synchronous chunk loads, 12 chunks generated ahead in parallel).
- Faster long-range picking.
- The planner pauses single player for real, like the Esc menu; building keeps running.
- Planner layout fixed for large GUI scales.

## Changes in 1.2.0

- Cancel planning button (and `/longtrack cancelplan`); cancelling a re-plan keeps the previous preview.
- The world froze while the planner was open (now a real pause in single player, see above).
- ETAs for planning, surveying and building in the planner and the hologram panel.

## Changes in 1.1.0

- **The route is searched, not drawn straight.** A terrain-aware search (weighted A* over track headings and heights)
  picks the line: it follows the ground and valleys, bends around hills instead of tunnelling through all of them, and
  only bridges where it has to. Flat, plain track is cheapest; embankments and shallow cuttings cost a little; land
  bridges and tunnels cost a lot; every bend costs extra and wiggling back and forth costs double, so lines stay
  fairly straight but not ruler-straight.
- **No more "too steep".** When the ends are further apart in height than the grade allows in a straight line, the
  search lengthens the line itself: switchbacks up a mountainside or a spiral (also through a mountain) for an even
  climb. Curves may now climb too, as Create allows.
- **Far fewer tracks.** One track item pays for 16 blocks of finished line (config `blocksPerTrackItem`), so a
  10,000 block route needs roughly 650 tracks instead of ~9,000.
- **Bridges mostly over water.** Bridging dry valleys is expensive for the search, so it goes around or follows the
  valley; bridges over rivers and lakes are cheap.
- **Tunnels only build inside mountains.** Tunnel walls and ceilings are placed only where the route is enclosed by
  terrain above and on both sides; cuttings and half-open hillsides are just cleared.
- **Level tunnels and viaducts.** Inside a tunnel the line runs on one steady grade between the portals, and on a
  bridge on one steady grade between the abutments (no more up-and-down humps).
- **Bigger clearance.** Tunnels and cuttings are 7 wide and 8 high (room for players riding on top of a carriage),
  9 wide on curves so long carriages don't clip the walls (configurable).
- **Styles.** In the planner (or with commands) pick:
  - floor block: bridge decks, track beds, embankments, tunnel floors
  - wall block: tunnel walls and ceilings, bridge pillars
  - lights: none, torches, lanterns or soul lanterns (every 10 blocks in tunnels and on bridges)
  - build: bridges and tunnels, tunnels only, bridges only, or **clear only** (only clears the way and lays the
    track, which floats over gaps)
- Bridge pillars are single columns every 8 blocks (no more wall-like pillar rows on curves).

## Requirements

| Mod | Needed | Notes |
|-----|--------|-------|
| NeoForge | 21.1.219+ | |
| Create | 6.0.0+ (built against 6.0.10) | required |
| Xaero's World Map + Xaero Train Map | optional (client) | routes are drawn on the world map, and you can set start and end points there |
| Voxy Atmosphere Compat | optional (client), any version | holograms and long-range picking reach as far as Voxy/SeedGen draw terrain; found through its stable `VoxyAtmoApi`, so newer versions keep working |
| JJThunder To The Max | optional (datapack) | a terrain profile made for it switches on automatically |

## How to use

1. Craft a **Track Surveyor**: a spyglass on top, andesite alloy, compass, andesite alloy across the middle, and Create track at the bottom.
2. **Start point:** right-click a block. Right-clicking an existing straight track block makes a junction, or extends the line if that block is a dead end.
3. **End point:** right-click again. You can set either point without the area being loaded:
   - aim at distant terrain (for example Voxy LOD terrain) or at the horizon and right-click. The server finds where your line of sight meets the world-gen terrain, even in chunks that have never been generated.
   - open Xaero's World Map and press `[` (start) or `]` (end) at the cursor.
   - `/longtrack start|end <x y z>` or `/longtrack startxz|endxz <x> <z>`.
4. The route is searched and shown as a **hologram**: green on the ground, cyan on bridges, orange in tunnels. Long routes over rough terrain can take a few seconds to plan.
5. **Sneak + right-click** opens the planner: floor block, wall block, track type, lights and what to build. Changing any of them re-plans the preview. Press **Confirm**.
6. The route is **surveyed**: its chunks are loaded (or generated) one by one with chunk tickets to count the exact materials.
7. Press **Deposit**. It pulls the materials out of your inventory and out of shulker boxes you carry. Once everything has been supplied, the line is **built** chunk by chunk, wherever the players are.

## Commands

`/longtrack start|end|startxz|endxz|plan|cancelplan|clear|confirm|deposit|cancel|list|status`,
`/longtrack floor <block>`, `/longtrack walls <block>` (the old `bridge`/`lining` names still work),
`/longtrack light none|torch|lantern|soul_lantern`, `/longtrack structures full|tunnels_only|bridges_only|clear_only`.

Operator only: `/longtrack route <x1> <z1> <x2> <z2>` (works from the console), `/longtrack admin confirm|fill|cancel <id>`, `/longtrack fill <id>`.

## Config

Server, `longhaultracks-server.toml`:
- `route`: max length, grade, embankment limit, water clearance, tunnel height/width, extra width on curves, pillar spacing/depth, sampling
- `search`: grid size, step budget, and the costs of bends, tunnels, land bridges and water bridges (raise `tunnelCost` for more detours, lower it for more tunnels; raise `turnCost` for straighter lines)
- `build`: `blocksPerTrackItem`, light spacing, build speed, chunks generated ahead (`prefetchChunks`), survey first, creative is free, far-pick range
- `pause`: `pauseWhilePlanning`, `pauseOnServers`, and the building speed while paused
- `compat`: the JJThunder profile

Client, `longhaultracks-client.toml`: map overlay, hologram detail range, whether holograms always show.

## JJThunder To The Max

On 1.21.1 JJThunder To The Max is a datapack (world height 2096, mountains near y 2000). The planner samples terrain through the dimension's own noise router, so it sees JJThunder's real terrain, including chunks that don't exist yet. When the pack is detected (pack id, or a world at least 1024 tall) the search uses a 32-block grid and twice the step budget, tunnels are cheaper relative to detours (its mountains are kilometres wide), pillars may go 1024 deep, and terrain is sampled every 8 blocks. `/longtrack status` shows what was detected.

## Tested

`source/src/gametest` holds a headless in-world test (`./gradlew runGameTestServer`, Create jar in `run-gametest/mods/`), all in chunks that were never loaded before:
- **climb**: from the plain to the summit of a 60-block cone whose sides are far steeper than the grade. Planned as a 750-block spiral (partly through the mountain), built in clear-only mode with only 52 tracks.
- **route**: 900 × 420 blocks across a hill, two cliffs with a valley, and a lake, all too wide to go around. Built with lanterns; 58 tracks and 780 stone bricks (1.0.0 needed 1,070 tracks and 2,198 bricks on similar terrain).
- **switchback**: the same kind of cone climb, forced onto the switchback layout, surveyed and built in full
  (660 blocks, every track and curve connected, one train graph).
- **junction**: a branch off the middle of that route, with torches.
- **cancel**: a 10,800-block plan cancelled right away leaves no job behind.
- **planner benches** (`PlannerBench`): a 1,116-block climb over 2,400 blocks on synthetic JJThunder-scale terrain,
  and, when the JJThunder datapack is in `run-gametest/world/datapacks/`, the terrain sampler checked against the
  generator's own heights plus a real JJThunder climb from a valley to a peak (1,387 blocks up, solved with
  switchbacks). `-PlhtSerp=true` forces the switchback layout for both.

Each route checks that a build ETA is reported, every track block and curve, at least 6 blocks of head room over the rails, and that the whole line is one Create train graph. Not tested yet: a real client (hologram, GUI, map, the pause) and building in an actual JJThunder world (planning was tested against the real JJThunder generator).
