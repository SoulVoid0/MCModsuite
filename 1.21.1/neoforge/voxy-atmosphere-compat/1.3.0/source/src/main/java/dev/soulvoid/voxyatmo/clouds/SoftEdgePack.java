package dev.soulvoid.voxyatmo.clouds;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import dev.soulvoid.voxyatmo.core.Config;
import dev.soulvoid.voxyatmo.core.ModIds;

/**
 * Built-in resource pack that overrides Simple Clouds' {@code clouds} and {@code clouds_transparency} fragment shaders:
 * far clouds fade to transparent instead of to opaque fog colour. Always on and placed on top while {@code softCloudEdges} is enabled; otherwise it is only
 * listed, off, in the resource pack screen.
 */
public final class SoftEdgePack {

    public static void register(final IEventBus modBus) {
        if (!ModIds.isLoaded(ModIds.SIMPLE_CLOUDS))
            return;
        modBus.addListener(SoftEdgePack::onAddPackFinders);
    }

    private static void onAddPackFinders(final AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES)
            return;
        event.addPackFinders(
            ResourceLocation.fromNamespaceAndPath(ModIds.SELF, "resourcepacks/soft_cloud_edges"),
            PackType.CLIENT_RESOURCES,
            Component.literal("Voxy Atmosphere Compat: soft cloud edges"),
            PackSource.BUILT_IN,
            Config.softCloudEdges(),
            Pack.Position.TOP);
    }

    private SoftEdgePack() {}
}
