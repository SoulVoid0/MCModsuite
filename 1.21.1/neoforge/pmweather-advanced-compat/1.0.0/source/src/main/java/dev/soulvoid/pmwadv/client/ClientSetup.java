package dev.soulvoid.pmwadv.client;

import java.util.Comparator;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import dev.protomanly.pmweather.cm1.Cm1Helper;
import dev.protomanly.pmweather.event.GameBusClientEvents;
import dev.protomanly.pmweather.weather.Storm;
import dev.protomanly.pmweather.weather.WeatherHandler;
import dev.soulvoid.pmwadv.core.Config;
import dev.soulvoid.pmwadv.core.StackCheck;
import dev.soulvoid.pmwadv.net.AreaNetwork;

public final class ClientSetup {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static int lastSent = Integer.MIN_VALUE;
    private static boolean sortBroken;

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientSetup::onTick);
        NeoForge.EVENT_BUS.addListener(ClientSetup::onLogin);
        NeoForge.EVENT_BUS.addListener(ClientSetup::onCommands);
    }

    private static void onLogin(final ClientPlayerNetworkEvent.LoggingIn event) {
        lastSent = Integer.MIN_VALUE;
    }

    private static void onTick(final ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null)
            return;
        if (mc.level.getGameTime() % 100 == 0 || lastSent == Integer.MIN_VALUE)
            reportArea(mc);
        if (Config.nearestFirst())
            sortStorms(mc);
    }

    private static void reportArea(final Minecraft mc) {
        final int blocks = Config.reportDistance() ? VoxyDistance.areaBlocks() : -1;
        if (blocks == lastSent || !mc.getConnection().hasChannel(AreaNetwork.ReportArea.TYPE))
            return;
        PacketDistributor.sendToServer(new AreaNetwork.ReportArea(blocks));
        lastSent = blocks;
    }

    /**
     * CM1's shader pass draws only the first CM1-driven supercell and the first cyclone of the client's storm list
     * within 32000 blocks. The list is in spawn order, so with storms spread over a large area a far storm could
     * hide the one overhead. Keep the list nearest-first (stable sort, under CM1's render lock).
     */
    private static void sortStorms(final Minecraft mc) {
        if (sortBroken)
            return;
        try {
            final WeatherHandler handler = GameBusClientEvents.weatherHandler;
            if (handler == null)
                return;
            final List<Storm> storms = handler.getStorms();
            if (storms.size() < 2)
                return;
            final Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            final Comparator<Storm> nearest = Comparator.comparingDouble(s -> {
                final double dx = s.position.x - cam.x, dz = s.position.z - cam.z;
                return dx * dx + dz * dz;
            });
            synchronized (Cm1Helper.RENDER_LOCK) {
                for (int i = 1; i < storms.size(); i++)
                    if (nearest.compare(storms.get(i - 1), storms.get(i)) > 0) {
                        storms.sort(nearest);
                        break;
                    }
            }
        } catch (final RuntimeException | LinkageError e) {
            sortBroken = true;
            LOGGER.warn("[PMWeather Advanced Compat] cannot reorder PMWeather's client storm list, nearest-first off", e);
        }
    }

    private static void onCommands(final RegisterClientCommandsEvent event) {
        final CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("pmwadv").then(Commands.literal("status").executes(ctx -> {
            final CommandSourceStack src = ctx.getSource();
            final int area = VoxyDistance.areaBlocks();
            src.sendSystemMessage(Component.literal("PMWeather Advanced Compat").withStyle(s -> s.withBold(true)));
            src.sendSystemMessage(Component.literal("Storm area reported: " + (area > 0 ? area + " blocks" : "none")
                + " (" + VoxyDistance.source() + ")"
                + (lastSent == area ? "" : ", not sent yet")));
            StackCheck.PARTS.forEach((id, name) -> {
                final String v = StackCheck.version(id);
                src.sendSystemMessage(Component.literal("  " + name + ": " + (v == null ? "missing" : v)));
            });
            final WeatherHandler h = GameBusClientEvents.weatherHandler;
            if (h != null) {
                final Vec3 p = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                src.sendSystemMessage(Component.literal("Storms known to this client: " + h.getStorms().size()));
                for (final Storm s : h.getStorms())
                    src.sendSystemMessage(Component.literal(String.format("  #%d %s stage %d at %.0f, %.0f (%.0f blocks)",
                        s.ID, s.stormType == null ? "?" : s.stormType.getId(), s.stage, s.position.x, s.position.z,
                        Math.sqrt((s.position.x - p.x) * (s.position.x - p.x) + (s.position.z - p.z) * (s.position.z - p.z)))));
            }
            return 1;
        })));
    }

    private ClientSetup() {}
}
