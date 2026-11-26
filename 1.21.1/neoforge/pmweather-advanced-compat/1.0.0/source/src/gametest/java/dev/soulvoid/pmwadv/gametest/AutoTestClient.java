package dev.soulvoid.pmwadv.gametest;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * -Dpmwadv.autotest=true: on the title screen create a fresh world (seed 7), run every GameTest of this mod on the
 * integrated server with /test runall, wait for them, quit. Results are in the log ("[PMWADV test]" and GameTest's
 * own pass/fail lines).
 */
@EventBusSubscriber(modid = "pmweatheradvcompat", value = Dist.CLIENT)
public final class AutoTestClient {

    private static final Logger LOG = LogUtils.getLogger();
    private static final boolean ON = Boolean.getBoolean("pmwadv.autotest");
    private static boolean created;
    private static int serverTicks;
    private static boolean started;

    @SubscribeEvent
    public static void onTitle(final ScreenEvent.Init.Post event) {
        if (!ON || created || !(event.getScreen() instanceof TitleScreen))
            return;
        created = true;
        final Minecraft mc = Minecraft.getInstance();
        final String name = "pmwadv-" + System.currentTimeMillis();
        LOG.info("[PMWADV test] creating world {}", name);
        final GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        mc.createWorldOpenFlows().createFreshLevel(name,
            new LevelSettings(name, GameType.CREATIVE, false, Difficulty.NORMAL, true, rules, WorldDataConfiguration.DEFAULT),
            new WorldOptions(7L, false, false), WorldPresets::createNormalWorldDimensions, event.getScreen());
    }

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event) {
        if (!ON)
            return;
        final MinecraftServer server = event.getServer();
        serverTicks++;
        if (!started && serverTicks == 100) {
            started = true;
            LOG.info("[PMWADV test] running /test runall");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPermission(4)
                .withSuppressedOutput(), "test runall");
            return;
        }
        if (started && serverTicks > 200) {
            if (StormAreaTests.finished() >= StormAreaTests.TOTAL || serverTicks > 20 * 60 * 40) {
                LOG.info("[PMWADV test] done: {} of {} tests finished, {} failed; quitting", StormAreaTests.finished(),
                    StormAreaTests.TOTAL, StormAreaTests.failures());
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
                started = false;
                serverTicks = Integer.MIN_VALUE;
            }
        }
    }

    private AutoTestClient() {}
}
