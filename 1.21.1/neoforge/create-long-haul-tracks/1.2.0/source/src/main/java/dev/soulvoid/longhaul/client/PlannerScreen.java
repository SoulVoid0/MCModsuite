package dev.soulvoid.longhaul.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.simibubi.create.content.trains.track.TrackMaterial;

import dev.soulvoid.longhaul.job.JobManager;
import dev.soulvoid.longhaul.net.Net;
import dev.soulvoid.longhaul.route.Style;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;

/** The surveyor's planner: points, bridge/lining/track materials, and the bill of materials of each route. */
public final class PlannerScreen extends Screen {

    private int routeIndex = -1;

    public PlannerScreen() {
        super(Component.translatable("longhaultracks.gui.title"));
    }

    @Override
    protected void init() {
        rebuild();
        PacketDistributor.sendToServer(new Net.PlannerOpen(true));
    }

    @Override
    public void removed() {
        PacketDistributor.sendToServer(new Net.PlannerOpen(false));
        super.removed();
    }

    private void rebuild() {
        clearWidgets();
        final int l = width / 2 - 200, t = 40;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycle(Net.Action.SET_BRIDGE, -1)).bounds(l + 100, t + 40, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycle(Net.Action.SET_BRIDGE, 1)).bounds(l + 180, t + 40, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycle(Net.Action.SET_LINING, -1)).bounds(l + 100, t + 62, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycle(Net.Action.SET_LINING, 1)).bounds(l + 180, t + 62, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleTrack(-1)).bounds(l + 100, t + 84, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleTrack(1)).bounds(l + 180, t + 84, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleEnum(Net.Action.SET_LIGHT, Style.Light.values(), -1)).bounds(l + 100, t + 106, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleEnum(Net.Action.SET_LIGHT, Style.Light.values(), 1)).bounds(l + 180, t + 106, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleEnum(Net.Action.SET_MODE, Style.Mode.values(), -1)).bounds(l + 100, t + 128, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleEnum(Net.Action.SET_MODE, Style.Mode.values(), 1)).bounds(l + 180, t + 128, 16, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("longhaultracks.gui.replan"), b -> send(null, Net.Action.REPLAN, ""))
            .bounds(l, t + 152, 95, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("longhaultracks.gui.clear"), b -> send(null, Net.Action.CLEAR_POINTS, ""))
            .bounds(l + 100, t + 152, 95, 20).build());
        if (ClientRoutes.draft != null && ClientRoutes.draft.planning())
            addRenderableWidget(Button.builder(Component.translatable("longhaultracks.gui.cancel_plan"), b -> send(null, Net.Action.CANCEL_PLAN, ""))
                .bounds(l, t + 196, 195, 20).build());

        final int r = width / 2 + 10;
        final int by = height - 34;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { routeIndex--; rebuild(); }).bounds(r, t - 4, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { routeIndex++; rebuild(); }).bounds(r + 20, t - 4, 16, 16).build());
        final ClientRoutes.Route route = selected();
        if (route != null && route.status != null) {
            final int st = route.status.state();
            if (st == 0)
                addRenderableWidget(Button.builder(Component.translatable("longhaultracks.gui.confirm"), b -> act(Net.Action.CONFIRM))
                    .bounds(r, by, 60, 20).build());
            if (st != 1 && st != 5)
                addRenderableWidget(Button.builder(Component.translatable("longhaultracks.gui.deposit"), b -> act(Net.Action.DEPOSIT))
                    .bounds(r + 64, by, 60, 20).build());
            if (minecraft.player.isCreative() || minecraft.player.hasPermissions(2))
                addRenderableWidget(Button.builder(Component.translatable("longhaultracks.gui.fill"), b -> act(Net.Action.FILL))
                    .bounds(r + 128, by, 50, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("longhaultracks.gui.cancel"), b -> act(Net.Action.CANCEL))
                .bounds(r + 182, by, 50, 20).build());
        }
    }

    private ClientRoutes.Route selected() {
        final List<ClientRoutes.Route> mine = ClientRoutes.mine();
        if (mine.isEmpty())
            return null;
        if (routeIndex < 0 || routeIndex >= mine.size())
            routeIndex = Math.floorMod(routeIndex, mine.size());
        return mine.get(routeIndex);
    }

    private void act(final Net.Action a) {
        final ClientRoutes.Route r = selected();
        if (r != null)
            send(r.id, a, "");
    }

    private static void send(final java.util.UUID id, final Net.Action a, final String arg) {
        PacketDistributor.sendToServer(new Net.JobAction(id, a, arg));
    }

    /** Candidates: valid blocks in the inventory, plus a few common defaults. */
    private List<ResourceLocation> candidates() {
        final Set<ResourceLocation> out = new LinkedHashSet<>();
        for (final Block b : new Block[] {Blocks.STONE_BRICKS, Blocks.DEEPSLATE_BRICKS, Blocks.BRICKS, Blocks.SMOOTH_STONE,
            Blocks.POLISHED_ANDESITE, Blocks.SPRUCE_PLANKS})
            out.add(BuiltInRegistries.BLOCK.getKey(b));
        final var inv = minecraft.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            final ItemStack s = inv.getItem(i);
            if (JobManager.validStructureItem(s))
                out.add(BuiltInRegistries.BLOCK.getKey(((BlockItem) s.getItem()).getBlock()));
        }
        return new ArrayList<>(out);
    }

    private void cycle(final Net.Action a, final int d) {
        final Net.Draft dr = ClientRoutes.draft;
        final List<ResourceLocation> c = candidates();
        final String cur = dr == null ? "" : (a == Net.Action.SET_BRIDGE ? dr.bridge() : dr.lining());
        int i = c.indexOf(ResourceLocation.tryParse(cur));
        i = Math.floorMod(i + d, c.size());
        send(null, a, c.get(i).toString());
    }

    private void cycleEnum(final Net.Action a, final Enum<?>[] values, final int d) {
        final Net.Draft dr = ClientRoutes.draft;
        final String cur = dr == null ? "" : (a == Net.Action.SET_LIGHT ? dr.light() : dr.mode());
        int i = 0;
        for (int k = 0; k < values.length; k++)
            if (values[k].name().equalsIgnoreCase(cur))
                i = k;
        send(null, a, values[Math.floorMod(i + d, values.length)].name().toLowerCase(java.util.Locale.ROOT));
    }

    private void cycleTrack(final int d) {
        final List<ResourceLocation> ids = new ArrayList<>(TrackMaterial.ALL.keySet());
        ids.sort(null);
        final Net.Draft dr = ClientRoutes.draft;
        int i = dr == null ? -1 : ids.indexOf(ResourceLocation.tryParse(dr.track()));
        i = Math.floorMod(i + d, ids.size());
        send(null, Net.Action.SET_TRACK, ids.get(i).toString());
    }

    @Override
    public void tick() {
        // status packets may add or remove buttons
        final ClientRoutes.Route r = selected();
        final int state = (r == null || r.status == null ? -1 : r.status.state())
            + (ClientRoutes.draft != null && ClientRoutes.draft.planning() ? 100 : 0);
        if (state != lastState) {
            lastState = state;
            rebuild();
        }
    }

    private int lastState = -2;

    @Override
    public void render(final GuiGraphics g, final int mx, final int my, final float pt) {
        super.render(g, mx, my, pt);
        final int l = width / 2 - 200, t = 40;
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        final Net.Draft d = ClientRoutes.draft;
        g.drawString(font, Component.translatable("longhaultracks.gui.start", fmt(d == null ? null : d.start())), l, t, 0xFFDD33);
        g.drawString(font, Component.translatable("longhaultracks.gui.end", fmt(d == null ? null : d.end())), l, t + 14, 0xFF6655);
        g.drawString(font, Component.translatable("longhaultracks.gui.bridge"), l, t + 44, 0xFFFFFF);
        g.drawString(font, Component.translatable("longhaultracks.gui.lining"), l, t + 66, 0xFFFFFF);
        g.drawString(font, Component.translatable("longhaultracks.gui.track"), l, t + 88, 0xFFFFFF);
        g.drawString(font, Component.translatable("longhaultracks.gui.light"), l, t + 110, 0xFFFFFF);
        g.drawString(font, Component.translatable("longhaultracks.gui.mode"), l, t + 132, 0xFFFFFF);
        if (d != null) {
            item(g, d.bridge(), l + 120, t + 40);
            item(g, d.lining(), l + 120, t + 62);
            final TrackMaterial tm = TrackMaterial.ALL.get(ResourceLocation.tryParse(d.track()));
            if (tm != null)
                g.renderItem(new ItemStack(tm.getBlock()), l + 120, t + 84);
            g.drawCenteredString(font, Component.translatable("longhaultracks.light." + d.light()), l + 148, t + 110, 0xCCCCCC);
            g.drawCenteredString(font, Component.translatable("longhaultracks.mode." + d.mode()), l + 148, t + 132, 0xCCCCCC);
            if (d.planning())
                g.drawString(font, Component.translatable("longhaultracks.msg.planning").append(" " + (int) (d.progress() * 100) + "%")
                    .append(d.eta() >= 0 ? Component.translatable("longhaultracks.gui.eta", HologramRenderer.duration(d.eta())) : Component.empty()),
                    l, t + 178, 0xAAAAFF);
            else if (!d.message().isEmpty())
                g.drawWordWrap(font, Component.literal(d.message()), l, t + 178, 190, 0xFF8888);
        }
        g.drawWordWrap(font, Component.translatable("longhaultracks.gui.help"), l, height - 70, 190, 0x999999);

        final int r = width / 2 + 10;
        final ClientRoutes.Route route = selected();
        if (route == null || route.status == null) {
            g.drawString(font, Component.translatable("longhaultracks.gui.no_routes"), r + 44, t, 0xAAAAAA);
            return;
        }
        g.drawString(font, (routeIndex + 1) + "/" + ClientRoutes.mine().size() + "  " + route.status.start().toShortString()
            + " -> " + route.status.end().toShortString(), r + 44, t, 0xFFFFFF);
        int y = t + 18;
        for (final Component line : HologramRenderer.lines(route.status)) {
            g.drawString(font, line, r, y, 0xDDDDDD);
            y += 11;
            if (y > height - 40)
                break;
        }
        if (route.status.startJunction() || route.status.endJunction())
            g.drawString(font, Component.translatable("longhaultracks.gui.junction"), r, Math.min(y + 4, height - 48), 0xCC88FF);
    }

    private void item(final GuiGraphics g, final String id, final int x, final int y) {
        final ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null)
            return;
        final Block b = BuiltInRegistries.BLOCK.get(rl);
        g.renderItem(new ItemStack(b), x, y);
        g.drawString(font, b.getName(), x + 80, y + 4, 0xCCCCCC);
    }

    private static String fmt(final BlockPos p) {
        return p == null ? "-" : p.getX() + " " + p.getY() + " " + p.getZ();
    }

    /** Not a vanilla pause (that would stop the server, building included): the server freezes the world instead. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
