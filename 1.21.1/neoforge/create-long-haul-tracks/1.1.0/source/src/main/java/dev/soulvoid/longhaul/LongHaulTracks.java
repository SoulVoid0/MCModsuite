package dev.soulvoid.longhaul;

import dev.soulvoid.longhaul.command.LongTrackCommand;
import dev.soulvoid.longhaul.item.SurveyorItem;
import dev.soulvoid.longhaul.job.JobManager;
import dev.soulvoid.longhaul.net.Net;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Create: Long Haul Tracks. Pick two points (either may be far outside the loaded area), get a hologram of a
 * terrain-following route with tunnels and bridges plus its bill of materials, supply the materials, and the route is
 * built chunk by chunk, joining existing track with junctions where a point is on a line.
 */
@Mod(LongHaulTracks.MOD_ID)
public final class LongHaulTracks {
    public static final String MOD_ID = "longhaultracks";

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredItem<SurveyorItem> SURVEYOR =
        ITEMS.register("track_surveyor", () -> new SurveyorItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    public LongHaulTracks(final IEventBus modBus, final ModContainer container) {
        ITEMS.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
        modBus.addListener(Net::register);
        modBus.addListener(LongHaulTracks::tabs);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> JobManager.get(e.getServer()).tick());
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp)
                JobManager.get(sp.server).onLogin(sp);
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> JobManager.get(e.getServer()).shutdown());
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> LongTrackCommand.register(e.getDispatcher(), e.getBuildContext()));
    }

    private static void tabs(final BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES)
            e.accept(SURVEYOR.get());
    }
}
