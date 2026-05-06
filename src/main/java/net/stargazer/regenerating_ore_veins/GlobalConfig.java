package net.stargazer.regenerating_ore_veins;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.neoforged.fml.loading.FMLPaths;

public final class GlobalConfig {
    public static final int DEFAULT_REGENERATION_SECONDS_VALUE = 3600;
    public static final int DEFAULT_SMOKE_PARTICLE_COUNT = 12;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve(RegeneratingOreVeins.MOD_ID);
    private static final Path GLOBAL_PATH = CONFIG_DIR.resolve("global.json");
    private static volatile Values cached;

    private GlobalConfig() {
    }

    public static Values get() {
        Values values = cached;
        if (values == null) {
            values = loadFromDisk();
        }

        return values;
    }

    public static Values loadFromDisk() {
        ensureDefaultFile();
        try (Reader reader = Files.newBufferedReader(GLOBAL_PATH)) {
            JsonObject object = GSON.fromJson(reader, JsonObject.class);
            Values values = parse(object == null ? new JsonObject() : object);
            cached = values;
            return values;
        } catch (IOException | JsonParseException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to read {}", GLOBAL_PATH, exception);
            Values fallback = Values.defaults();
            cached = fallback;
            return fallback;
        }
    }

    public static void ensureDefaultFile() {
        try {
            Files.createDirectories(CONFIG_DIR);
            if (Files.notExists(GLOBAL_PATH)) {
                try (Writer writer = Files.newBufferedWriter(GLOBAL_PATH)) {
                    GSON.toJson(toJson(Values.defaults()), writer);
                }
            } else {
                addMissingDefaultValues();
            }
        } catch (IOException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to initialise {}", GLOBAL_PATH, exception);
        }
    }

    private static void addMissingDefaultValues() throws IOException {
        JsonObject defaults = toJson(Values.defaults());
        JsonObject existing;
        try (Reader reader = Files.newBufferedReader(GLOBAL_PATH)) {
            existing = GSON.fromJson(reader, JsonObject.class);
        } catch (JsonParseException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to parse {}, leaving it unchanged", GLOBAL_PATH, exception);
            return;
        }

        if (existing == null) {
            existing = new JsonObject();
        }

        boolean changed = false;
        for (Map.Entry<String, JsonElement> entry : defaults.entrySet()) {
            if (!existing.has(entry.getKey())) {
                existing.add(entry.getKey(), entry.getValue());
                changed = true;
            }
        }

        if (changed) {
            try (Writer writer = Files.newBufferedWriter(GLOBAL_PATH)) {
                GSON.toJson(existing, writer);
            }
        }
    }

    private static Values parse(JsonObject object) {
        return new Values(
                getBoolean(object, "allow_breaking", true),
                Math.max(0.0D, getDouble(object, "break_hardness", 5.0D)),
                Math.max(1, getInt(object, "default_regeneration_seconds", DEFAULT_REGENERATION_SECONDS_VALUE)),
                parseJitter(object.getAsJsonObject("default_jitter_interval")),
                getBoolean(object, "regeneration_smoke_particles", true),
                getBoolean(object, "destroyed_by_explosives", false),
                clampUnit(getDouble(object, "default_fill_factor", 1.0D))
        );
    }

    private static VeinConfig.RegenerationIntervalJitter parseJitter(JsonObject object) {
        if (object == null) {
            return VeinConfig.RegenerationIntervalJitter.NONE;
        }

        int rangeMin = getInt(object, "range_min", 0);
        int rangeMax = getInt(object, "range_max", 0);
        return new VeinConfig.RegenerationIntervalJitter(Math.min(rangeMin, rangeMax), Math.max(rangeMin, rangeMax));
    }

    private static JsonObject toJson(Values values) {
        JsonObject object = new JsonObject();
        object.addProperty("allow_breaking", values.allowBreaking());
        object.addProperty("break_hardness", values.breakHardness());
        object.addProperty("default_regeneration_seconds", values.defaultRegenerationSeconds());
        JsonObject jitter = new JsonObject();
        jitter.addProperty("range_min", values.defaultJitterInterval().rangeMin());
        jitter.addProperty("range_max", values.defaultJitterInterval().rangeMax());
        object.add("default_jitter_interval", jitter);
        object.addProperty("regeneration_smoke_particles", values.regenerationSmokeParticles());
        object.addProperty("destroyed_by_explosives", values.destroyedByExplosives());
        object.addProperty("default_fill_factor", values.defaultFillFactor());
        return object;
    }

    private static double clampUnit(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        return object.has(key) ? object.get(key).getAsBoolean() : fallback;
    }

    private static double getDouble(JsonObject object, String key, double fallback) {
        return object.has(key) ? object.get(key).getAsDouble() : fallback;
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }

    public record Values(
            boolean allowBreaking,
            double breakHardness,
            int defaultRegenerationSeconds,
            VeinConfig.RegenerationIntervalJitter defaultJitterInterval,
            boolean regenerationSmokeParticles,
            boolean destroyedByExplosives,
            double defaultFillFactor
    ) {
        public static Values defaults() {
            return new Values(true, 5.0D, DEFAULT_REGENERATION_SECONDS_VALUE, VeinConfig.RegenerationIntervalJitter.NONE, true, false, 1.0D);
        }
    }
}
