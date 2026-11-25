# Voxy Atmosphere Compat 1.3.0 (NeoForge 1.21.1)

**Jar:** `voxy-atmosphere-compat-1.3.0.jar` in this folder. Same targets as [1.2.0](../1.2.0/README.md), plus tested
with Iris 1.8.14-beta.1 and Complementary Reimagined r5.10. Everything from 1.2.0 is included; same `VoxyAtmoApi`.

## Fix: clouds drawn over Voxy's far terrain with a shaderpack
Shaderpacks that render Voxy's LODs themselves (Complementary and others; `excludeLodsFromVanillaDepth` in their
`voxy.json`) keep the LOD depth in Voxy's own framebuffer and never write it into Minecraft's depth buffer. Simple
Clouds copies only Minecraft's depth before drawing clouds, so it could not see LOD terrain and drew clouds over
distant mountains.
1.3.0 takes Voxy's LOD depth each frame (from its Iris pipeline) and merges it into Simple Clouds' cloud depth right
after the depth copy, with Voxy's own depth re-projection shader. Without a shaderpack nothing changes (Voxy already
writes that depth). Log line on first use: `merged LOD depth into the cloud depth`.

## Change: storm-fog rain shafts off by default
Simple Clouds' storm fog ray-marches rain shafts from the cloud base down to Y 0. With clouds lifted above tall
terrain (1.2.0) they hung in mid-air as dark purple columns. New option `stormFogShafts` (default false).

## Note: flat white blocky clouds
Flat, unshaded white clouds are the shaderpack's own clouds, not Simple Clouds'. The shaderpack draws them itself
and they do not know about Voxy's terrain. Turn them off in the shaderpack settings (Complementary: Atmospherics,
Clouds, Cloud Style: Off).
