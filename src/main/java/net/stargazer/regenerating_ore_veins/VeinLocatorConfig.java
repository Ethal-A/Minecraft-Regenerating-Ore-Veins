package net.stargazer.regenerating_ore_veins;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

public final class VeinLocatorConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve(RegeneratingOreVeins.MOD_ID);
    private static final Path LOCATOR_PATH = CONFIG_DIR.resolve("vein_locator.json");
    private static volatile Values cached;

    private VeinLocatorConfig() {
    }

    public static Values get() {
        Values values = cached;
        return values == null ? loadFromDisk() : values;
    }

    public static Values loadFromDisk() {
        ensureDefaultFile();
        try (Reader reader = Files.newBufferedReader(LOCATOR_PATH)) {
            JsonObject object = GSON.fromJson(reader, JsonObject.class);
            Values values = parse(object == null ? new JsonObject() : object);
            cached = values;
            return values;
        } catch (IOException | JsonParseException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to read {}", LOCATOR_PATH, exception);
            Values fallback = Values.defaults();
            cached = fallback;
            return fallback;
        }
    }

    public static void ensureDefaultFile() {
        try {
            Files.createDirectories(CONFIG_DIR);
            if (Files.notExists(LOCATOR_PATH)) {
                try (Writer writer = Files.newBufferedWriter(LOCATOR_PATH)) {
                    GSON.toJson(toJson(Values.defaults()), writer);
                }
            } else {
                addMissingDefaultValues();
            }
        } catch (IOException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to initialise {}", LOCATOR_PATH, exception);
        }
    }

    private static void addMissingDefaultValues() throws IOException {
        JsonObject defaults = toJson(Values.defaults());
        JsonObject existing;
        try (Reader reader = Files.newBufferedReader(LOCATOR_PATH)) {
            existing = GSON.fromJson(reader, JsonObject.class);
        } catch (JsonParseException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to parse {}, leaving it unchanged", LOCATOR_PATH, exception);
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
            try (Writer writer = Files.newBufferedWriter(LOCATOR_PATH)) {
                GSON.toJson(existing, writer);
            }
        }
    }

    private static Values parse(JsonObject object) {
        return new Values(
                getBoolean(object, "allow_crafting", true),
                getBoolean(object, "allow_use", true),
                parseRecipe(object.getAsJsonArray("crafting_recipe")),
                getBoolean(object, "craft_any_order", true),
                clampUnit(getDouble(object, "chance_to_break", 0.75D)),
                getBoolean(object, "use_durability", false),
                Math.max(1, getInt(object, "durability", 2)),
                MthClampStackSize(getInt(object, "stack_size", 64))
        );
    }

    private static List<ResourceLocation> parseRecipe(JsonArray array) {
        List<ResourceLocation> result = new ArrayList<>();
        if (array != null) {
            for (JsonElement element : array) {
                if (result.size() >= 9) {
                    RegeneratingOreVeins.LOGGER.warn("Ignoring extra vein locator crafting ingredients after the first 9 entries");
                    break;
                }

                if (!element.isJsonPrimitive()) {
                    continue;
                }

                ResourceLocation id = parseItemId(element.getAsString());
                if (id != null) {
                    result.add(id);
                }
            }
        }

        if (result.isEmpty()) {
            return Values.defaults().craftingRecipe();
        }

        return List.copyOf(result);
    }

    private static ResourceLocation parseItemId(String id) {
        try {
            ResourceLocation location = ResourceLocation.parse(id);
            if (ResourceLocation.fromNamespaceAndPath("minecraft", "netherite").equals(location)) {
                return ResourceLocation.fromNamespaceAndPath("minecraft", "netherite_ingot");
            }

            if (!BuiltInRegistries.ITEM.containsKey(location)) {
                RegeneratingOreVeins.LOGGER.warn("Unknown vein locator crafting item '{}'", id);
                return null;
            }

            return location;
        } catch (RuntimeException exception) {
            RegeneratingOreVeins.LOGGER.warn("Invalid vein locator crafting item '{}'", id, exception);
            return null;
        }
    }

    private static JsonObject toJson(Values values) {
        JsonObject object = new JsonObject();
        object.addProperty("allow_crafting", values.allowCrafting());
        object.addProperty("allow_use", values.allowUse());
        JsonArray recipe = new JsonArray();
        values.craftingRecipe().forEach(location -> recipe.add(location.toString()));
        object.add("crafting_recipe", recipe);
        object.addProperty("craft_any_order", values.craftAnyOrder());
        object.addProperty("chance_to_break", values.chanceToBreak());
        object.addProperty("use_durability", values.useDurability());
        object.addProperty("durability", values.durability());
        object.addProperty("stack_size", values.stackSize());
        return object;
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        return object.has(key) ? object.get(key).getAsBoolean() : fallback;
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        if (!object.has(key)) {
            return fallback;
        }

        try {
            long value = object.get(key).getAsLong();
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
        } catch (RuntimeException exception) {
            RegeneratingOreVeins.LOGGER.warn("Invalid integer value for vein locator config key '{}', using {}", key, fallback, exception);
            return fallback;
        }
    }

    private static double getDouble(JsonObject object, String key, double fallback) {
        return object.has(key) ? object.get(key).getAsDouble() : fallback;
    }

    private static double clampUnit(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static int MthClampStackSize(int value) {
        return Math.max(1, Math.min(64, value));
    }

    public record Values(
            boolean allowCrafting,
            boolean allowUse,
            List<ResourceLocation> craftingRecipe,
            boolean craftAnyOrder,
            double chanceToBreak,
            boolean useDurability,
            int durability,
            int stackSize
    ) {
        public static Values defaults() {
            return new Values(
                    true,
                    true,
                    List.of(
                            ResourceLocation.fromNamespaceAndPath("minecraft", "netherite_ingot"),
                            ResourceLocation.fromNamespaceAndPath("minecraft", "ender_eye")
                    ),
                    true,
                    0.75D,
                    false,
                    2,
                    64
            );
        }
    }
}
