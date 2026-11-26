package dev.soulvoid.pmwadv.core;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.neoforged.fml.ModList;

/** Which parts of the weather stack are installed. Logged once at start and shown by /pmwadv status. */
public final class StackCheck {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Map<String, String> PARTS = new LinkedHashMap<>();
    static {
        PARTS.put("pmweather", "ProtoManly's Weather");
        PARTS.put("pmweathercm1", "PMWeatherCM1");
        PARTS.put("pmocean", "PMPHYSICS");
        PARTS.put("pmweather_aeronautics", "PMWeather Aeronautics compat");
        PARTS.put("sable", "Sable");
        PARTS.put("aeronautics", "Create Aeronautics");
        PARTS.put("voxy", "Voxy");
        PARTS.put("voxyseedgen", "Voxy SeedGen");
    }

    public static String version(final String modId) {
        return ModList.get().getModContainerById(modId)
            .map(c -> c.getModInfo().getVersion().toString()).orElse(null);
    }

    public static void logAtStartup() {
        final StringBuilder sb = new StringBuilder("[PMWeather Advanced Compat] stack:");
        PARTS.forEach((id, name) -> {
            final String v = version(id);
            sb.append("\n  ").append(name).append(" (").append(id).append("): ").append(v == null ? "not installed" : v);
        });
        LOGGER.info(sb.toString());
        if (version("pmocean") == null)
            LOGGER.warn("[PMWeather Advanced Compat] PMPHYSICS (pmocean) is not loaded. It asks for PMWeather 0.17+, while "
                + "PMWeatherCM1 needs 0.16.4: add  dependencyOverrides.pmocean = [\"-pmweather\"]  to config/fml.toml "
                + "(see the mod's README).");
        if (version("pmweather_aeronautics") == null)
            LOGGER.info("[PMWeather Advanced Compat] PMWeather Aeronautics compat not installed: Aeronautics creations "
                + "will not feel storm wind.");
    }

    private StackCheck() {}
}
