package dev.soulvoid.longhaul.client;

import dev.soulvoid.longhaul.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client side of the surveyor item. */
public final class ClientActions {

    public static void openPlanner() {
        Minecraft.getInstance().setScreen(new PlannerScreen());
    }

    /**
     * Aiming at something beyond reach: first ray-cast the terrain the client has loaded; if the ray leaves the loaded
     * area (e.g. it ends on Voxy LOD terrain or the endpoint is simply not rendered) let the server find the hit from
     * world-gen, which needs no chunk to exist.
     */
    public static void longRangePick() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null)
            return;
        final Vec3 eye = mc.player.getEyePosition();
        final Vec3 look = mc.player.getViewVector(1);
        final double loaded = mc.options.getEffectiveRenderDistance() * 16.0;
        final BlockHitResult hit = mc.level.clip(new ClipContext(eye, eye.add(look.scale(loaded)),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
        if (hit.getType() == HitResult.Type.BLOCK && mc.level.isLoaded(hit.getBlockPos())) {
            PacketDistributor.sendToServer(new Net.PickBlock(hit.getBlockPos()));
            return;
        }
        PacketDistributor.sendToServer(new Net.FarPick(eye, look));
    }

    private ClientActions() {}
}
