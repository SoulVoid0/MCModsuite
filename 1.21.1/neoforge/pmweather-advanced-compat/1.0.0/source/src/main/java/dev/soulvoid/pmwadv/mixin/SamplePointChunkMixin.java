package dev.soulvoid.pmwadv.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;

import dev.protomanly.pmweather.weather.ThermodynamicEngine;
import dev.soulvoid.pmwadv.core.Config;

/**
 * PMWeather's atmosphere sampler loads the chunk under the sample point ({@code level.getChunk(pos)}) only to look
 * for fires in it. PMWeatherCM1 samples a sounding at every candidate storm position, so a storm placed far out
 * made the server generate that chunk synchronously: seconds per spawn check in a JJThunder world. An unloaded
 * chunk has no burning blocks anyway, so it is answered with an empty chunk instead. Loaded chunks are unchanged.
 */
@Mixin(value = ThermodynamicEngine.class, remap = false)
public abstract class SamplePointChunkMixin {

    @Redirect(method = "samplePoint(Ldev/protomanly/pmweather/weather/WeatherHandler;Lnet/minecraft/world/phys/Vec3;"
        + "Lnet/minecraft/world/level/Level;Ldev/protomanly/pmweather/block/entity/RadarBlockEntity;ILjava/lang/Integer;ZZ)"
        + "Ldev/protomanly/pmweather/weather/ThermodynamicEngine$AtmosphericDataPoint;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getChunk(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
        require = 0)
    private static ChunkAccess pmwadv$noFarChunkLoad(final Level level, final BlockPos pos) {
        if (level.isClientSide() || !Config.avoidFarChunkLoads() || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null)
            return level.getChunk(pos);
        return new EmptyLevelChunk(level, new ChunkPos(pos),
            level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
    }
}
