package dev.soulvoid.longhaul.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.soulvoid.longhaul.LongHaulTracks;
import dev.soulvoid.longhaul.job.JobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** All packets. Client-bound handlers go through {@code ClientHooks} so nothing client-only links on a server. */
public final class Net {

    private static ResourceLocation rl(final String p) {
        return ResourceLocation.fromNamespaceAndPath(LongHaulTracks.MOD_ID, p);
    }

    // ------------------------------------------------------------------------------------ client -> server

    /** Player picked an exact block (in loaded terrain). */
    public record PickBlock(BlockPos pos) implements CustomPacketPayload {
        public static final Type<PickBlock> TYPE = new Type<>(rl("pick_block"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PickBlock> CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, PickBlock::pos, PickBlock::new);

        @Override
        public Type<PickBlock> type() {
            return TYPE;
        }
    }

    /** Player aimed at terrain beyond the loaded chunks (e.g. Voxy LOD terrain): the server ray-marches world-gen. */
    public record FarPick(Vec3 origin, Vec3 dir) implements CustomPacketPayload {
        public static final Type<FarPick> TYPE = new Type<>(rl("far_pick"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FarPick> CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeDouble(p.origin.x); b.writeDouble(p.origin.y); b.writeDouble(p.origin.z);
                b.writeDouble(p.dir.x); b.writeDouble(p.dir.y); b.writeDouble(p.dir.z);
            },
            b -> new FarPick(new Vec3(b.readDouble(), b.readDouble(), b.readDouble()),
                new Vec3(b.readDouble(), b.readDouble(), b.readDouble())));

        @Override
        public Type<FarPick> type() {
            return TYPE;
        }
    }

    /** Player set a point on Xaero's World Map; the server finds the surface. */
    public record MapPick(int x, int z, boolean start) implements CustomPacketPayload {
        public static final Type<MapPick> TYPE = new Type<>(rl("map_pick"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MapPick> CODEC = StreamCodec.of(
            (b, p) -> { b.writeVarInt(p.x); b.writeVarInt(p.z); b.writeBoolean(p.start); },
            b -> new MapPick(b.readVarInt(), b.readVarInt(), b.readBoolean()));

        @Override
        public Type<MapPick> type() {
            return TYPE;
        }
    }

    /** SET_BRIDGE sets the floor block, SET_LINING the wall block; SET_LIGHT/SET_MODE take Style names. */
    public enum Action { CONFIRM, CANCEL, DEPOSIT, FILL, SET_BRIDGE, SET_LINING, SET_TRACK, CLEAR_POINTS, REPLAN, SET_LIGHT, SET_MODE, CANCEL_PLAN }

    /** GUI button. {@code job} may be null for draft-level actions; {@code arg} is a registry id for SET_*. */
    public record JobAction(UUID job, Action action, String arg) implements CustomPacketPayload {
        public static final Type<JobAction> TYPE = new Type<>(rl("job_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, JobAction> CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeBoolean(p.job != null);
                if (p.job != null)
                    b.writeUUID(p.job);
                b.writeEnum(p.action);
                b.writeUtf(p.arg == null ? "" : p.arg);
            },
            b -> new JobAction(b.readBoolean() ? b.readUUID() : null, b.readEnum(Action.class), b.readUtf()));

        @Override
        public Type<JobAction> type() {
            return TYPE;
        }
    }

    /** The planner screen opened or closed (the world freezes while it is open, where allowed). */
    public record PlannerOpen(boolean open) implements CustomPacketPayload {
        public static final Type<PlannerOpen> TYPE = new Type<>(rl("planner_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlannerOpen> CODEC = StreamCodec.of(
            (b, p) -> b.writeBoolean(p.open), b -> new PlannerOpen(b.readBoolean()));

        @Override
        public Type<PlannerOpen> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------------------------ server -> client

    /** Route centerline for holograms and maps. Coordinates are quantised to 1/8 block. */
    public record Geometry(UUID id, String dim, int[] xyz, byte[] kinds) implements CustomPacketPayload {
        public static final Type<Geometry> TYPE = new Type<>(rl("geometry"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Geometry> CODEC = StreamCodec.of(
            (b, p) -> {
                UUIDUtil.STREAM_CODEC.encode(b, p.id);
                b.writeUtf(p.dim);
                b.writeVarInt(p.kinds.length);
                int lx = 0, ly = 0, lz = 0;
                for (int i = 0; i < p.kinds.length; i++) {
                    b.writeVarInt(zig(p.xyz[i * 3] - lx));
                    b.writeVarInt(zig(p.xyz[i * 3 + 1] - ly));
                    b.writeVarInt(zig(p.xyz[i * 3 + 2] - lz));
                    lx = p.xyz[i * 3];
                    ly = p.xyz[i * 3 + 1];
                    lz = p.xyz[i * 3 + 2];
                }
                b.writeByteArray(p.kinds);
            },
            b -> {
                final UUID id = UUIDUtil.STREAM_CODEC.decode(b);
                final String dim = b.readUtf();
                final int n = b.readVarInt();
                final int[] xyz = new int[n * 3];
                int lx = 0, ly = 0, lz = 0;
                for (int i = 0; i < n; i++) {
                    lx += unzig(b.readVarInt());
                    ly += unzig(b.readVarInt());
                    lz += unzig(b.readVarInt());
                    xyz[i * 3] = lx;
                    xyz[i * 3 + 1] = ly;
                    xyz[i * 3 + 2] = lz;
                }
                return new Geometry(id, dim, xyz, b.readByteArray());
            });

        @Override
        public Type<Geometry> type() {
            return TYPE;
        }
    }

    public record MatLine(String item, long required, long supplied) {}

    /** Everything the GUI and the hologram's info panel show about a job. */
    public record Status(UUID id, UUID owner, String ownerName, String dim, int state, String message, float progress,
                         BlockPos start, BlockPos end, boolean startJunction, boolean endJunction, int length,
                         int bridgeLength, int tunnelLength, String bridge, String lining, String track,
                         String light, String mode, boolean exact, List<MatLine> materials, int eta) implements CustomPacketPayload {
        public static final Type<Status> TYPE = new Type<>(rl("status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Status> CODEC = StreamCodec.of(
            (b, p) -> {
                UUIDUtil.STREAM_CODEC.encode(b, p.id);
                UUIDUtil.STREAM_CODEC.encode(b, p.owner);
                b.writeUtf(p.ownerName);
                b.writeUtf(p.dim);
                b.writeVarInt(p.state);
                b.writeUtf(p.message);
                b.writeFloat(p.progress);
                b.writeBlockPos(p.start);
                b.writeBlockPos(p.end);
                b.writeBoolean(p.startJunction);
                b.writeBoolean(p.endJunction);
                b.writeVarInt(p.length);
                b.writeVarInt(p.bridgeLength);
                b.writeVarInt(p.tunnelLength);
                b.writeUtf(p.bridge);
                b.writeUtf(p.lining);
                b.writeUtf(p.track);
                b.writeUtf(p.light);
                b.writeUtf(p.mode);
                b.writeBoolean(p.exact);
                b.writeVarInt(p.materials.size());
                for (final MatLine m : p.materials) {
                    b.writeUtf(m.item);
                    b.writeVarLong(m.required);
                    b.writeVarLong(m.supplied);
                }
                b.writeInt(p.eta);
            },
            b -> {
                final UUID id = UUIDUtil.STREAM_CODEC.decode(b);
                final UUID owner = UUIDUtil.STREAM_CODEC.decode(b);
                final String ownerName = b.readUtf();
                final String dim = b.readUtf();
                final int state = b.readVarInt();
                final String message = b.readUtf();
                final float progress = b.readFloat();
                final BlockPos start = b.readBlockPos(), end = b.readBlockPos();
                final boolean sj = b.readBoolean(), ej = b.readBoolean();
                final int len = b.readVarInt(), bl = b.readVarInt(), tl = b.readVarInt();
                final String bridge = b.readUtf(), lining = b.readUtf(), track = b.readUtf();
                final String light = b.readUtf(), mode = b.readUtf();
                final boolean exact = b.readBoolean();
                final int n = b.readVarInt();
                final List<MatLine> mats = new ArrayList<>();
                for (int i = 0; i < n; i++)
                    mats.add(new MatLine(b.readUtf(), b.readVarLong(), b.readVarLong()));
                return new Status(id, owner, ownerName, dim, state, message, progress, start, end, sj, ej, len, bl, tl,
                    bridge, lining, track, light, mode, exact, mats, b.readInt());
            });

        @Override
        public Type<Status> type() {
            return TYPE;
        }
    }

    public record Remove(UUID id) implements CustomPacketPayload {
        public static final Type<Remove> TYPE = new Type<>(rl("remove"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Remove> CODEC =
            StreamCodec.composite(UUIDUtil.STREAM_CODEC, Remove::id, Remove::new);

        @Override
        public Type<Remove> type() {
            return TYPE;
        }
    }

    /** The player's own selection (points not yet planned) and chosen materials. */
    public record Draft(String dim, BlockPos start, BlockPos end, String bridge, String lining, String track,
                        String light, String mode, String message, boolean planning, float progress, int eta) implements CustomPacketPayload {
        public static final Type<Draft> TYPE = new Type<>(rl("draft"));
        static final BlockPos NONE = new BlockPos(0, Integer.MIN_VALUE >> 8, 0);
        public static final StreamCodec<RegistryFriendlyByteBuf, Draft> CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeUtf(p.dim);
                b.writeBoolean(p.start != null);
                if (p.start != null) b.writeBlockPos(p.start);
                b.writeBoolean(p.end != null);
                if (p.end != null) b.writeBlockPos(p.end);
                b.writeUtf(p.bridge);
                b.writeUtf(p.lining);
                b.writeUtf(p.track);
                b.writeUtf(p.light);
                b.writeUtf(p.mode);
                b.writeUtf(p.message);
                b.writeBoolean(p.planning);
                b.writeFloat(p.progress);
                b.writeInt(p.eta);
            },
            b -> new Draft(b.readUtf(), b.readBoolean() ? b.readBlockPos() : null, b.readBoolean() ? b.readBlockPos() : null,
                b.readUtf(), b.readUtf(), b.readUtf(), b.readUtf(), b.readUtf(), b.readUtf(), b.readBoolean(), b.readFloat(),
                b.readInt()));

        @Override
        public Type<Draft> type() {
            return TYPE;
        }
    }

    private static int zig(final int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static int unzig(final int v) {
        return (v >>> 1) ^ -(v & 1);
    }

    // ----------------------------------------------------------------------------------------- register

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar r = event.registrar("3");
        r.playToServer(PickBlock.TYPE, PickBlock.CODEC, (p, ctx) -> server(ctx, pl -> JobManager.get(pl.server).pickBlock(pl, p.pos())));
        r.playToServer(FarPick.TYPE, FarPick.CODEC, (p, ctx) -> server(ctx, pl -> JobManager.get(pl.server).farPick(pl, p.origin(), p.dir())));
        r.playToServer(MapPick.TYPE, MapPick.CODEC, (p, ctx) -> server(ctx, pl -> JobManager.get(pl.server).mapPick(pl, p.x(), p.z(), p.start())));
        r.playToServer(JobAction.TYPE, JobAction.CODEC, (p, ctx) -> server(ctx, pl -> JobManager.get(pl.server).action(pl, p.job(), p.action(), p.arg())));
        r.playToServer(PlannerOpen.TYPE, PlannerOpen.CODEC, (p, ctx) -> server(ctx, pl -> JobManager.get(pl.server).plannerOpen(pl, p.open())));
        r.playToClient(Geometry.TYPE, Geometry.CODEC, (p, ctx) -> ctx.enqueueWork(() -> dev.soulvoid.longhaul.client.ClientRoutes.onGeometry(p)));
        r.playToClient(Status.TYPE, Status.CODEC, (p, ctx) -> ctx.enqueueWork(() -> dev.soulvoid.longhaul.client.ClientRoutes.onStatus(p)));
        r.playToClient(Remove.TYPE, Remove.CODEC, (p, ctx) -> ctx.enqueueWork(() -> dev.soulvoid.longhaul.client.ClientRoutes.onRemove(p)));
        r.playToClient(Draft.TYPE, Draft.CODEC, (p, ctx) -> ctx.enqueueWork(() -> dev.soulvoid.longhaul.client.ClientRoutes.onDraft(p)));
    }

    private static void server(final IPayloadContext ctx, final java.util.function.Consumer<ServerPlayer> body) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp)
                body.accept(sp);
        });
    }

    private Net() {}
}
