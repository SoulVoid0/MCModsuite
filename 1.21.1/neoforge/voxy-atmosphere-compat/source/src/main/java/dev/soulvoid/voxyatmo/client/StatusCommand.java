package dev.soulvoid.voxyatmo.client;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import dev.soulvoid.voxyatmo.core.ModIds;
import dev.soulvoid.voxyatmo.distance.SeedgenDistance;
import dev.soulvoid.voxyatmo.distance.TargetDistance;

/** {@code /voxyatmo status}: what is installed and which distances are in force. */
public final class StatusCommand {

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("voxyatmo").then(Commands.literal("status").executes(ctx -> {
            final CommandSourceStack src = ctx.getSource();
            src.sendSuccess(() -> Component.literal("[Voxy Atmosphere Compat]"), false);
            src.sendSuccess(() -> Component.literal(" voxy=" + ModIds.isLoaded(ModIds.VOXY)
                + " simpleclouds=" + ModIds.isLoaded(ModIds.SIMPLE_CLOUDS)
                + " projectatmosphere=" + ModIds.isLoaded(ModIds.PROJECT_ATMOSPHERE)
                + " seedgen=" + ModIds.seedgenModId()), false);
            final int seedgen = SeedgenDistance.chunks();
            src.sendSuccess(() -> Component.literal(" SeedGen distance: "
                + (seedgen > 0 ? seedgen + " chunks (" + SeedgenDistance.source() + ")" : "unknown")), false);
            if (ModIds.isLoaded(ModIds.VOXY))
                src.sendSuccess(() -> Component.literal(" Voxy LOD distance: "
                    + dev.soulvoid.voxyatmo.distance.VoxyBridge.renderDistanceBlocks() + " blocks"), false);
            src.sendSuccess(() -> Component.literal(" Distant terrain: " + TargetDistance.terrainBlocks()
                + " blocks, cloud target: " + TargetDistance.cloudBlocks() + " blocks"), false);
            if (ModIds.allLoaded(ModIds.VOXY, ModIds.SIMPLE_CLOUDS))
                src.sendSuccess(() -> Component.literal(" " + CloudStatus.describe()), false);
            return 1;
        })));
    }

    private StatusCommand() {}
}
