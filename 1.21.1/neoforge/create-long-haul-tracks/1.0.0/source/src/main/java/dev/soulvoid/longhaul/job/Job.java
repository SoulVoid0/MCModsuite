package dev.soulvoid.longhaul.job;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.simibubi.create.content.trains.track.TrackMaterial;

import dev.soulvoid.longhaul.route.Piece;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** One planned or running route. Saved in {@link JobManager}. */
public final class Job {

    public enum State {
        /** Planned; the player can change the materials and confirm or cancel. */
        PREVIEW,
        /** Walking the route chunk by chunk (loading them with tickets) to count the exact materials. */
        SURVEYING,
        /** Waiting for the player to supply the materials. */
        AWAITING_MATERIALS,
        BUILDING,
        /** Building stopped because the world needed more than was counted. */
        NEEDS_MATERIALS,
        COMPLETE
    }

    public final UUID id;
    public UUID owner;
    public String ownerName;
    public ResourceKey<Level> dim;
    public State state = State.PREVIEW;
    public String message = "";
    public BlockPos start, end;
    public boolean startJunction, endJunction;
    public ResourceLocation trackMaterial;
    public ResourceLocation bridgeBlock;
    public ResourceLocation liningBlock;
    public List<Piece> pieces = new ArrayList<>();
    public int length, bridgeLength, tunnelLength;
    /** Item id -> count. {@code exact} once a survey finished. */
    public Map<String, Long> required = new LinkedHashMap<>();
    public Map<String, Long> supplied = new LinkedHashMap<>();
    public boolean exact;
    public int cursor;
    public Map<String, Long> surveyCounts = new LinkedHashMap<>();

    transient Corridor corridor;
    transient int lastSync = -1;

    public Job(final UUID id) {
        this.id = id;
    }

    public TrackMaterial material() {
        final TrackMaterial m = trackMaterial == null ? null : TrackMaterial.ALL.get(trackMaterial);
        return m != null ? m : TrackMaterial.ANDESITE;
    }

    public Corridor corridor(final int pillarSpacing) {
        if (corridor == null)
            corridor = Corridor.build(pieces, material(), pillarSpacing);
        return corridor;
    }

    public int chunkCount(final int pillarSpacing) {
        return corridor(pillarSpacing).chunks.size();
    }

    public long missing(final String item) {
        return Math.max(0, required.getOrDefault(item, 0L) - supplied.getOrDefault(item, 0L));
    }

    public boolean fullySupplied() {
        for (final String k : required.keySet())
            if (missing(k) > 0)
                return false;
        return true;
    }

    public CompoundTag save() {
        final CompoundTag t = new CompoundTag();
        t.putUUID("Id", id);
        t.putUUID("Owner", owner);
        t.putString("OwnerName", ownerName);
        t.putString("Dim", dim.location().toString());
        t.putString("State", state.name());
        t.putString("Message", message);
        t.putLong("Start", start.asLong());
        t.putLong("End", end.asLong());
        t.putBoolean("StartJ", startJunction);
        t.putBoolean("EndJ", endJunction);
        t.putString("Track", material().id.toString());
        t.putString("Bridge", bridgeBlock.toString());
        t.putString("Lining", liningBlock.toString());
        final ListTag p = new ListTag();
        for (final Piece piece : pieces)
            p.add(piece.save());
        t.put("Pieces", p);
        t.putInt("Length", length);
        t.putInt("BridgeLen", bridgeLength);
        t.putInt("TunnelLen", tunnelLength);
        t.put("Required", map(required));
        t.put("Supplied", map(supplied));
        t.put("Survey", map(surveyCounts));
        t.putBoolean("Exact", exact);
        t.putInt("Cursor", cursor);
        return t;
    }

    public static Job load(final CompoundTag t) {
        final Job j = new Job(t.getUUID("Id"));
        j.owner = t.getUUID("Owner");
        j.ownerName = t.getString("OwnerName");
        j.dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(t.getString("Dim")));
        try {
            j.state = State.valueOf(t.getString("State"));
        } catch (final IllegalArgumentException e) {
            j.state = State.PREVIEW;
        }
        j.message = t.getString("Message");
        j.start = BlockPos.of(t.getLong("Start"));
        j.end = BlockPos.of(t.getLong("End"));
        j.startJunction = t.getBoolean("StartJ");
        j.endJunction = t.getBoolean("EndJ");
        j.trackMaterial = ResourceLocation.parse(t.getString("Track"));
        j.bridgeBlock = ResourceLocation.parse(t.getString("Bridge"));
        j.liningBlock = ResourceLocation.parse(t.getString("Lining"));
        for (final Tag p : t.getList("Pieces", Tag.TAG_COMPOUND))
            j.pieces.add(Piece.load((CompoundTag) p));
        j.length = t.getInt("Length");
        j.bridgeLength = t.getInt("BridgeLen");
        j.tunnelLength = t.getInt("TunnelLen");
        j.required = unmap(t.getCompound("Required"));
        j.supplied = unmap(t.getCompound("Supplied"));
        j.surveyCounts = unmap(t.getCompound("Survey"));
        j.exact = t.getBoolean("Exact");
        j.cursor = t.getInt("Cursor");
        return j;
    }

    private static CompoundTag map(final Map<String, Long> m) {
        final CompoundTag t = new CompoundTag();
        m.forEach(t::putLong);
        return t;
    }

    private static Map<String, Long> unmap(final CompoundTag t) {
        final Map<String, Long> m = new LinkedHashMap<>();
        for (final String k : t.getAllKeys())
            m.put(k, t.getLong(k));
        return m;
    }
}
