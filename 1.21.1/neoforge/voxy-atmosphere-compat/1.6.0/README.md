# Voxy Atmosphere Compat 1.6.0 (NeoForge 1.21.1)

**Jar:** `voxy-atmosphere-compat-1.6.0.jar` in this folder. Same targets as [1.5.0](../1.5.0/README.md). Everything
from 1.5.0 is included; same `VoxyAtmoApi` (apiVersion 1).

## New: cloud height config
`voxyatmocompat-client.toml`, section `cloud_look_and_coverage`:

```toml
cloudHeight = 1024
```

- Default **1024**: Simple Clouds' cloud base is set to Y 1024 in every world, existing worlds included.
- Range 0 to 2048 (Simple Clouds' own limit). Any value in that range fixes the clouds at that Y and overrides the
  automatic height.
- `-1` turns the fixed height off: `autoCloudHeight` and its options (percentile, margin, clear radius) decide the
  height again, as in 1.2.0 to 1.5.0.
- The value is checked every 5 seconds, so a change in the config file applies without restarting.
- Single player / LAN host only, like the automatic height (Simple Clouds stores the height on the server).

Tested in a JJThunder To The Max world: the log showed `cloud height 1072 -> 1024 (cloudHeight config)`, and Simple
Clouds on the client reported height 1024.

## Build
`cd source && ./gradlew build` (NeoForge 21.1.219, ModDevGradle). Drop the bridged mods' jars into `source/libs`
first (see `libs/README.md`).
