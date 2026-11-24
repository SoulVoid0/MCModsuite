# Create: Long Haul Tracks 1.0.0 (NeoForge 1.21.1)

Plan and build Create train lines over very long distances. Either end can be far outside the loaded or rendered area.

Jar: `create-long-haul-tracks-1.0.0.jar`. Source: `source/`.

## Requirements

| Mod | Needed | Notes |
|-----|--------|-------|
| NeoForge | 21.1.219+ | |
| Create | 6.0.0+ (built against 6.0.10) | required |
| Xaero's World Map + Xaero Train Map | optional (client) | routes are drawn on the world map, and you can set start and end points there |
| Voxy Atmosphere Compat | optional (client), any version | holograms and long-range picking reach as far as Voxy/SeedGen draw terrain |
| JJThunder To The Max | optional (datapack) | a terrain profile made for it switches on automatically |

## How to use

1. Craft a **Track Surveyor**: a spyglass on top, andesite alloy, compass, andesite alloy across the middle, and Create track at the bottom.
2. **Start point:** right-click a block. Right-clicking an existing straight track block makes a junction, or extends the line if that block is a dead end.
3. **End point:** right-click again. You can set either point without the area being loaded:
   - aim at distant terrain (for example Voxy LOD terrain) or at the horizon and right-click. The server finds where your line of sight meets the world-gen terrain, even in chunks that have never been generated.
   - open Xaero's World Map and press `[` (start) or `]` (end) at the cursor.
   - `/longtrack start|end <x y z>` or `/longtrack startxz|endxz <x> <z>`.
4. The route is planned and shown as a **hologram**: green on the ground, cyan on bridges, orange in tunnels. Close up you also see rails, sleepers, pillars and tunnel outlines. Floating panels at both ends show the bill of materials.
5. **Sneak + right-click** opens the planner. There you pick the **bridge block**, the tunnel lining block and the track type (any Create track material), then press **Confirm**.
6. The route is **surveyed**: its chunks are loaded (or generated) one by one with chunk tickets to count the exact materials.
7. Press **Deposit**. It pulls the materials out of your inventory and out of shulker boxes you carry. Once everything has been supplied, the line is **built** chunk by chunk, wherever the players are.

## What gets built

- **Track that follows the ground**, within a grade limit (default 3 in 16, which Create accepts). It uses Create's own straight tracks, sloped curves, 45° and 90° turns, and S-bends for small offsets.
- **Tunnels** through rises: a 5-wide, 5-high bore. Water, lava, falling blocks and cave openings along the bore are sealed with the lining block, so tunnels through cave systems (including JJThunder's underlands) end up as closed tubes.
- **Bridges** over valleys, rivers and lakes: a 3-wide deck of your bridge block, with pillars down to the ground every 8 blocks. Shallow dips are filled as embankments instead.
- **Junctions**: when a point is on existing straight track, the new line branches off it with a curve, which Create treats as a switch. Where the new line crosses existing track at the same height, a Create crossing is placed.
- Track graph registration happens as each chunk is built, so trains can use the line straight away.

## JJThunder To The Max

On 1.21.1 JJThunder To The Max is a datapack, not a mod. It makes the world 2096 blocks tall, with mountains near y 2000. The planner samples terrain through the dimension's own noise router, so it plans against JJThunder's real terrain, including chunks that don't exist yet. When the pack is detected (from its pack id, or from a world at least 1024 blocks tall), these settings change:
- tunnels are preferred over very tall bridges, more strongly in mountain biomes
- pillars may go 1024 blocks deep
- terrain is sampled every 8 blocks, because each 2096-tall column is expensive to evaluate
- terrain is smoothed over a wider area, so the line doesn't follow every crag

Check what was detected with `/longtrack status`.

## Commands

`/longtrack start|end|startxz|endxz|plan|clear|confirm|deposit|cancel|list|status`, and `/longtrack bridge <block>` / `/longtrack lining <block>`.

These need operator permission: `/longtrack route <x1> <z1> <x2> <z2>` (works from the console), `/longtrack admin confirm|fill|cancel <id>`, `/longtrack fill <id>`.

## Config

The server config is `longhaultracks-server.toml`. It covers the grade, bridge threshold, tunnel height, pillar spacing and depth, the tunnel versus bridge preference, smoothing, sampling, build speed (chunks and milliseconds per tick), the far-pick range, whether to survey first, whether creative players build for free, and the JJThunder profile.

The client config is `longhaultracks-client.toml`. It covers the map overlay, hologram detail range, and whether holograms always show.

## Tested

`source/src/gametest` contains a headless in-world test: `./gradlew runGameTestServer`, with the Create jar in `run-gametest/mods/`. It shapes a hill, a valley and a lake across a 900 × 420 block diagonal route, then plans, surveys and builds it in chunks that were never loaded. It checks every track block and curve, and checks that the whole line is a single Create train graph. Then it branches a second route off the middle of the first and checks the junction.
