package dev.soulvoid.pmwadv.mixin;

import org.spongepowered.asm.mixin.Mixin;

import dev.protomanly.pmweather.event.GameBusEvents;

/**
 * Empty: only gives {@link dev.soulvoid.pmwadv.core.Cm1SpawnRadiusPlugin} a postApply hook on GameBusEvents after
 * PMWeatherCM1's GameBusEventsMixin (priority 1000) has merged its spawner into it.
 */
@Mixin(value = GameBusEvents.class, priority = 2000, remap = false)
public abstract class Cm1SpawnerAnchorMixin {
}
