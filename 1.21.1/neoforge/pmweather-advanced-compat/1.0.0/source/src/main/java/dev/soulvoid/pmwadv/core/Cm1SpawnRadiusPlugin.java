package dev.soulvoid.pmwadv.core;

import java.util.List;
import java.util.Set;

import com.mojang.logging.LogUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * PMWeatherCM1 1.1.7 places its simulated (scenario) storms uniformly in a disc of hard-coded radius 10000 around a
 * player ({@code 10000 * sqrt(random)}), independent of PMWeather's spawnRange. After CM1's GameBusEventsMixin is
 * merged into GameBusEvents, this swaps that one constant for
 * {@link dev.soulvoid.pmwadv.area.StormAreaDriver#cm1ScenarioRadius()}, so CM1 still decides if and which storm
 * forms, but where reaches the Voxy area. Matched by shape ({@code ldc 10000.0; RANDOM.nextDouble(); Math.sqrt}),
 * so a CM1 update that changes it simply leaves the code untouched (logged).
 */
public final class Cm1SpawnRadiusPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TARGET = "dev.protomanly.pmweather.event.GameBusEvents";
    public static volatile int patched = -1;

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName,
                          final IMixinInfo mixinInfo) {
        if (!TARGET.equals(targetClassName))
            return;
        int count = 0;
        for (final MethodNode m : targetClass.methods) {
            if (!m.name.contains("onLevelTickInject"))
                continue;
            for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof LdcInsnNode ldc && Double.valueOf(10000.0).equals(ldc.cst) && isRandomSqrt(n)) {
                    final AbstractInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/soulvoid/pmwadv/area/StormAreaDriver", "cm1ScenarioRadius", "()D", false);
                    m.instructions.set(n, call);
                    n = call;
                    count++;
                }
            }
        }
        patched = count;
        if (count != 1)
            LOGGER.warn("[PMWeather Advanced Compat] GameBusEvents methods at postApply: {}",
                targetClass.methods.stream().map(m -> m.name).toList());
        if (count == 1)
            LOGGER.info("[PMWeather Advanced Compat] CM1 scenario spawn radius now follows the storm area");
        else
            LOGGER.warn("[PMWeather Advanced Compat] expected 1 CM1 scenario spawn radius, patched {}: CM1 changed? "
                + "Simulated storms keep CM1's own 10000-block radius.", count);
    }

    private static boolean isRandomSqrt(AbstractInsnNode n) {
        // getstatic PMWeather.RANDOM; invokevirtual Random.nextDouble; invokestatic Math.sqrt
        n = next(n);
        if (n == null || n.getOpcode() != Opcodes.GETSTATIC)
            return false;
        n = next(n);
        if (!(n instanceof MethodInsnNode a) || !"nextDouble".equals(a.name))
            return false;
        n = next(a);
        return n instanceof MethodInsnNode b && "sqrt".equals(b.name) && "java/lang/Math".equals(b.owner);
    }

    private static AbstractInsnNode next(AbstractInsnNode n) {
        n = n.getNext();
        while (n != null && n.getOpcode() < 0)
            n = n.getNext();
        return n;
    }

    @Override public void onLoad(final String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) { return true; }
    @Override public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(final String t, final ClassNode c, final String m, final IMixinInfo i) {}
}
