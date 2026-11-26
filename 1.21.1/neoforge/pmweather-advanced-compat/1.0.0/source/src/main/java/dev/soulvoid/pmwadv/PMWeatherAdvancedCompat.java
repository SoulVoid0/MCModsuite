package dev.soulvoid.pmwadv;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

import dev.soulvoid.pmwadv.area.StormAreaDriver;
import dev.soulvoid.pmwadv.client.ClientSetup;
import dev.soulvoid.pmwadv.core.Config;
import dev.soulvoid.pmwadv.core.StackCheck;
import dev.soulvoid.pmwadv.net.AreaNetwork;

@Mod(PMWeatherAdvancedCompat.MOD_ID)
public final class PMWeatherAdvancedCompat {

    public static final String MOD_ID = "pmweatheradvcompat";

    public PMWeatherAdvancedCompat(final IEventBus modBus, final ModContainer container, final Dist dist) {
        container.registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
        modBus.addListener(AreaNetwork::register);
        NeoForge.EVENT_BUS.register(StormAreaDriver.class);
        if (dist.isClient())
            ClientSetup.init();
        StackCheck.logAtStartup();
    }
}
