package dev.soulvoid.pmwadv.net;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import dev.soulvoid.pmwadv.PMWeatherAdvancedCompat;
import dev.soulvoid.pmwadv.area.StormAreaDriver;

/** Client -> server: how far (blocks) this player's Voxy / Voxy SeedGen terrain reaches. Optional channel. */
public final class AreaNetwork {

    public record ReportArea(int blocks) implements CustomPacketPayload {
        public static final Type<ReportArea> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PMWeatherAdvancedCompat.MOD_ID, "report_area"));
        public static final StreamCodec<ByteBuf, ReportArea> CODEC =
            ByteBufCodecs.VAR_INT.map(ReportArea::new, ReportArea::blocks);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(final RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToServer(ReportArea.TYPE, ReportArea.CODEC, (payload, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp)
                StormAreaDriver.report(sp.getUUID(), payload.blocks());
        });
    }

    private AreaNetwork() {}
}
