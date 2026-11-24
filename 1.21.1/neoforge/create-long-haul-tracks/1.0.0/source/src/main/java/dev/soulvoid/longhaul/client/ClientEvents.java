package dev.soulvoid.longhaul.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import dev.soulvoid.longhaul.LongHaulTracks;
import dev.soulvoid.longhaul.net.Net;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ClientEvents {

    public static final KeyMapping MAP_START = new KeyMapping("key.longhaultracks.map_start", KeyConflictContext.GUI,
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, "key.categories.longhaultracks");
    public static final KeyMapping MAP_END = new KeyMapping("key.longhaultracks.map_end", KeyConflictContext.GUI,
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, "key.categories.longhaultracks");

    @EventBusSubscriber(modid = LongHaulTracks.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void keys(final RegisterKeyMappingsEvent e) {
            e.register(MAP_START);
            e.register(MAP_END);
        }
    }

    @EventBusSubscriber(modid = LongHaulTracks.MOD_ID, value = Dist.CLIENT)
    public static final class GameBus {
        @SubscribeEvent
        public static void render(final RenderLevelStageEvent e) {
            HologramRenderer.render(e);
        }

        /** Set route points on Xaero's World Map at the block under the cursor. */
        @SubscribeEvent
        public static void key(final ScreenEvent.KeyPressed.Pre e) {
            if (e.getScreen() == null || !e.getScreen().getClass().getName().equals("xaero.map.gui.GuiMap") || !MapOverlay.open)
                return;
            final InputConstants.Key key = InputConstants.getKey(e.getKeyCode(), e.getScanCode());
            if (MAP_START.isActiveAndMatches(key) || MAP_END.isActiveAndMatches(key)) {
                PacketDistributor.sendToServer(new Net.MapPick(MapOverlay.mouseX, MapOverlay.mouseZ, MAP_START.isActiveAndMatches(key)));
                e.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void tick(final ClientTickEvent.Post e) {
            final Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen != null && mc.screen.getClass().getName().equals("xaero.map.gui.GuiMap")))
                MapOverlay.open = false;
            if (mc.player == null || mc.level == null)
                return;
            // keep distant terrain drawn out to the far end of any visible route (Voxy Atmosphere Compat 1.1+)
            int want = 0;
            if (HologramRenderer.visible()) {
                final Vec3 p = mc.player.position();
                for (final ClientRoutes.Route r : ClientRoutes.routes()) {
                    if (!r.inCurrentDimension() || r.kinds.length == 0)
                        continue;
                    for (int i = 0; i < r.kinds.length; i += 16) {
                        final double dx = r.xyz[i * 3] - p.x, dz = r.xyz[i * 3 + 2] - p.z;
                        want = Math.max(want, (int) Math.sqrt(dx * dx + dz * dz));
                    }
                }
                want = (want + 511) / 512 * 512;
            }
            if (mc.level.getGameTime() % 20 == 0)
                DistantTerrain.request(want);
        }

        @SubscribeEvent
        public static void logout(final ClientPlayerNetworkEvent.LoggingOut e) {
            ClientRoutes.clear();
            DistantTerrain.request(0);
        }
    }

    private ClientEvents() {}
}
