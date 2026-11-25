# Create: Long Haul Tracks 1.3.0 (NeoForge 1.21.1)

Plan and build Create train lines over very long distances. Either end can be far outside the loaded or rendered area.

Jar: `create-long-haul-tracks-1.3.0.jar`. Source: `source/`. Replaces 1.0.0, 1.1.0 and 1.2.0 (install one version only).
Routes planned or half built with older versions keep working.

## Changes from 1.2.0

- **Planning no longer stalls at 89% on JJThunder.** Terrain heights of chunks that were never generated now come
  from probing the world generator's density directly instead of filling each whole 2096-block column: about 20
  times faster on JJThunder (measured against the real datapack: 1.1 ms instead of 25 ms per column, mean height
  error 1.3 blocks). Big climbs to mountain tops are also solved now: the search used to lose labels that had
  climbed (they shared an 8-block height band with cheaper ones that had not) and got stuck below the summit; it
  goes greedier straight away when the climb is much longer than the distance. Tested: a JJThunder climb of 632
  blocks and a 1,116-block climb over 2,400 blocks (like the one in the report) both plan.
- **Surveying and building no longer stall at 1%.** After 30 s of waiting for a chunk the builder used to load it
  synchronously, which froze the whole game on slow world generation (JJThunder) and then waited again for the
  next one. Now it never loads synchronously, queues 12 chunks ahead (config `prefetchChunks`) so the world
  generator works on them in parallel, and loads a 3x3 area per chunk instead of 5x5. While it waits, the status
  says how many of the queued chunks are ready.
- **Long-range picking is quicker** ("Surveying distant terrain..."): the faster terrain sampler plus longer strides
  while the line of sight is high above the ground.
- **A real pause in single player.** The planner now pauses the game like the Esc menu: time, weather, clouds, mobs
  and sounds all stop. Planning runs on its own threads, and surveying and building keep running in the paused
  ticks with up to 40 ms and 16 chunks per tick. On a LAN world with only the host online the world is frozen
  like `/tick freeze` instead (a published world can't pause), and dedicated servers only freeze with
  `pauseOnServers`.
- **Planner layout at large GUI scales.** The help text is only drawn where it fits (it overlapped the buttons and
  status line), and the route column no longer runs off the screen.

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
- **junction**: a branch off the middle of that route, with torches.
- **cancel**: a 10,800-block plan cancelled right away leaves no job behind.
- **planner benches** (`PlannerBench`): a 1,116-block climb over 2,400 blocks on synthetic JJThunder-scale terrain,
  and, when the JJThunder datapack is in `run-gametest/world/datapacks/`, the terrain sampler checked against the
  generator's own heights plus a real JJThunder climb from a valley to a peak.

Each route checks that a build ETA is reported, every track block and curve, at least 6 blocks of head room over the rails, and that the whole line is one Create train graph. Not tested yet: a real client (hologram, GUI, map, the pause) and building in an actual JJThunder world (planning was tested against the real JJThunder generator).
