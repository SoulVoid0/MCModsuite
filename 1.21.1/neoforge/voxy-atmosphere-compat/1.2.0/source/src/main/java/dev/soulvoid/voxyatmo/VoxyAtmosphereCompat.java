package dev.soulvoid.voxyatmo;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

import dev.soulvoid.voxyatmo.client.ClientHooks;
import dev.soulvoid.voxyatmo.clouds.CloudAltitude;
import dev.soulvoid.voxyatmo.clouds.SoftEdgePack;
import dev.soulvoid.voxyatmo.core.Config;
import dev.soulvoid.voxyatmo.core.ModIds;

@Mod(ModIds.SELF)
public final class VoxyAtmosphereCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    public VoxyAtmosphereCompat(final IEventBus modBus, final ModContainer container) {
        if (!FMLEnvironment.dist.isClient()) {
            LOGGER.info("[Voxy Atmosphere Compat] dedicated server: nothing to do (all patches are client side).");
            return;
        }
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        ClientHooks.register(NeoForge.EVENT_BUS);
        SoftEdgePack.register(modBus);
        if (ModIds.isLoaded(ModIds.SIMPLE_CLOUDS))
            CloudAltitude.register(NeoForge.EVENT_BUS);
        LOGGER.info("[Voxy Atmosphere Compat] voxy={} simpleclouds={} projectatmosphere={} seedgen={}",
            ModIds.isLoaded(ModIds.VOXY), ModIds.isLoaded(ModIds.SIMPLE_CLOUDS),
            ModIds.isLoaded(ModIds.PROJECT_ATMOSPHERE), ModIds.seedgenModId());
    }
}
