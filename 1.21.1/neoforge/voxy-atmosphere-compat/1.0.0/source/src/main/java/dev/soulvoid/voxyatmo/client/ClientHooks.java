package dev.soulvoid.voxyatmo.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import dev.soulvoid.voxyatmo.clouds.SimpleCloudsPatch;
import dev.soulvoid.voxyatmo.core.Config;
import dev.soulvoid.voxyatmo.core.ModIds;
import dev.soulvoid.voxyatmo.distance.SeedgenDistance;
import dev.soulvoid.voxyatmo.distance.VoxyBridge;

/** Client-side registration. Each handler references a bridged mod's classes only when that mod is loaded. */
public final class ClientHooks {

    private static int tick;

    public static void register(final IEventBus gameBus) {
        if (ModIds.allLoaded(ModIds.VOXY, ModIds.SIMPLE_CLOUDS))
            gameBus.register(SimpleCloudsPatch.class);
        if (ModIds.isLoaded(ModIds.VOXY) && ModIds.isSeedgenLoaded())
            gameBus.addListener(ClientHooks::onClientTick);
        gameBus.addListener(ClientHooks::onRegisterCommands);
    }

    /** Keeps Voxy's LOD render distance at or above SeedGen's generation distance (checked every 2 s). */
    private static void onClientTick(final ClientTickEvent.Post event) {
        if (++tick < 40)
            return;
        tick = 0;
        if (!Config.raiseVoxyRenderDistance())
            return;
        final int chunks = SeedgenDistance.chunks();
        if (chunks > 0)
            VoxyBridge.ensureRenderDistanceAtLeast(chunks * 16);
    }

    private static void onRegisterCommands(final RegisterClientCommandsEvent event) {
        StatusCommand.register(event.getDispatcher());
    }

    private ClientHooks() {}
}
