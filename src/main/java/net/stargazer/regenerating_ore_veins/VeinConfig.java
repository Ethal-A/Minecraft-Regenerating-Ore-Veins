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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;

public final class VeinConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_./-]+");
    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve(RegeneratingOreVeins.MOD_ID);
    private static final Path AREAS_PATH = CONFIG_DIR.resolve("areas.json");
    private static final Path VEINS_PATH = CONFIG_DIR.resolve("veins.json");
    private static volatile LoadedConfig cached = LoadedConfig.empty();

    private VeinConfig() {
    }

    public static LoadedConfig get() {
        return cached;
    }

    public static LoadedConfig loadFromDisk() {
        ensureDefaults();
        Map<String, AreaDefinition> areas = readAreas();
        Map<String, VeinDefinition> veins = readVeins(areas);
        cached = new LoadedConfig(areas, veins);
        return cached;
    }

    public static void ensureDefaults() {
        GlobalConfig.ensureDefaultFile();
        try {
            Files.createDirectories(CONFIG_DIR);
            if (Files.notExists(AREAS_PATH)) {
                writeDefaultAreas();
            }

            if (Files.notExists(VEINS_PATH)) {
                writeDefaultVeins();
            }
        } catch (IOException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to initialise {}", CONFIG_DIR, exception);
        }
    }

    public static Optional<VeinDefinition> getVein(String id) {
        LoadedConfig config = get();
        VeinDefinition direct = config.veinsById().get(id);
        if (direct != null) {
            return Optional.of(direct);
        }

        return Optional.ofNullable(loadFromDisk().veinsById().get(id));
    }

    private static Map<String, AreaDefinition> readAreas() {
        try (Reader reader = Files.newBufferedReader(AREAS_PATH)) {
            JsonArray array = GSON.fromJson(reader, JsonArray.class);
            if (array == null) {
                return Map.of();
            }

            Map<String, AreaDefinition> result = new LinkedHashMap<>();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }

                AreaDefinition area = parseArea(element.getAsJsonObject());
                if (area != null) {
                    result.put(area.name(), area);
                }
            }

            return result;
        } catch (IOException | JsonParseException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to read {}", AREAS_PATH, exception);
            return Map.of();
        }
    }

    private static Map<String, VeinDefinition> readVeins(Map<String, AreaDefinition> areas) {
        try (Reader reader = Files.newBufferedReader(VEINS_PATH)) {
            JsonArray array = GSON.fromJson(reader, JsonArray.class);
            if (array == null) {
                return Map.of();
            }

            Map<String, VeinDefinition> result = new LinkedHashMap<>();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }

                VeinDefinition vein = parseVein(element.getAsJsonObject(), areas);
                if (vein != null) {
                    result.put(vein.id(), vein);
                }
            }

            return result;
        } catch (IOException | JsonParseException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to read {}", VEINS_PATH, exception);
            return Map.of();
        }
    }

    private static AreaDefinition parseArea(JsonObject object) {
        String name = getString(object, "name", "");
        String type = getString(object, "type", "box");
        ResourceKey<Level> dimension = parseDimension(getString(object, "dimension", "minecraft:overworld"));
        if (name.isBlank()) {
            return null;
        }

        if (!"box".equals(type)) {
            RegeneratingOreVeins.LOGGER.warn("Unsupported area type '{}' for '{}'", type, name);
            return null;
        }

        return new AreaDefinition(
                name,
                dimension,
                getInt(object, "x", 0),
                getInt(object, "y", 64),
                getInt(object, "z", 0),
                Math.max(1, getInt(object, "dimx", 1)),
                Math.max(1, getInt(object, "dimy", 1)),
                Math.max(1, getInt(object, "dimz", 1))
        );
    }

    private static VeinDefinition parseVein(JsonObject object, Map<String, AreaDefinition> areas) {
        String id = getString(object, "id", "");
        if (id.isBlank() || !VALID_ID.matcher(id).matches()) {
            RegeneratingOreVeins.LOGGER.warn("Skipping vein with invalid id '{}'", id);
            return null;
        }

        List<BlockState> blockStates = new ArrayList<>();
        JsonArray blocks = object.getAsJsonArray("blocks");
        if (blocks == null || blocks.isEmpty()) {
            RegeneratingOreVeins.LOGGER.warn("Skipping vein '{}' because it has no blocks", id);
            return null;
        }

        for (JsonElement element : blocks) {
            BlockState state = parseBlockState(element.getAsString());
            if (state != null) {
                blockStates.add(state);
            }
        }

        if (blockStates.isEmpty()) {
            RegeneratingOreVeins.LOGGER.warn("Skipping vein '{}' because none of its blocks exist", id);
            return null;
        }

        List<Integer> weights = parseWeights(object.getAsJsonArray("weights"), blockStates.size());
        String areaName = object.has("area") ? getString(object, "area", "") : "";
        AreaDefinition area = areaName.isBlank() ? null : areas.get(areaName);
        List<ResourceKey<Level>> dimensions = area != null ? List.of(area.dimension()) : parseDimensions(object.get("dimension"));
        List<BiomeCriterion> biomes = parseBiomes(object.get("biome"));

        return new VeinDefinition(
                id,
                Collections.unmodifiableList(dimensions),
                Collections.unmodifiableList(biomes),
                Collections.unmodifiableList(blockStates),
                Collections.unmodifiableList(weights),
                VeinShape.fromName(getString(object, "shape", "circle")),
                Math.max(1, getInt(object, "min_size", 12)),
                Math.max(1, getInt(object, "max_size", 20)),
                Math.max(1, getInt(object, "attempts", 1)),
                getInt(object, "min_y", -64),
                getInt(object, "max_y", 320),
                Math.max(0.01D, getDouble(object, "chunk_minimum_generation_separation", 8.0D)),
                clampUnit(getDouble(object, "fill_factor", GlobalConfig.get().defaultFillFactor())),
                Math.max(1, getInt(object, "regeneration_interval_seconds", GlobalConfig.get().defaultRegenerationSeconds())),
                parseJitter(object.getAsJsonObject("regeneration_interval_jitter")),
                areaName,
                area
        );
    }

    private static RegenerationIntervalJitter parseJitter(JsonObject object) {
        if (object == null) {
            return GlobalConfig.get().defaultJitterInterval();
        }

        int rangeMin = getInt(object, "range_min", 0);
        int rangeMax = getInt(object, "range_max", 0);
        return new RegenerationIntervalJitter(Math.min(rangeMin, rangeMax), Math.max(rangeMin, rangeMax));
    }

    private static List<Integer> parseWeights(JsonArray array, int size) {
        List<Integer> weights = new ArrayList<>(size);
        if (array != null) {
            for (JsonElement element : array) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                    weights.add(Math.max(1, element.getAsInt()));
                }
            }
        }

        while (weights.size() < size) {
            weights.add(1);
        }

        if (weights.size() > size) {
            return new ArrayList<>(weights.subList(0, size));
        }

        return weights;
    }

    private static BlockState parseBlockState(String id) {
        try {
            ResourceLocation location = ResourceLocation.parse(id);
            if (!BuiltInRegistries.BLOCK.containsKey(location)) {
                RegeneratingOreVeins.LOGGER.warn("Unknown block '{}'", id);
                return null;
            }

            Block block = BuiltInRegistries.BLOCK.get(location);
            return block.defaultBlockState();
        } catch (RuntimeException exception) {
            RegeneratingOreVeins.LOGGER.warn("Invalid block id '{}'", id, exception);
            return null;
        }
    }

    private static ResourceKey<Level> parseDimension(String id) {
        try {
            ResourceLocation location = ResourceLocation.parse(id);
            if (ResourceLocation.fromNamespaceAndPath("minecraft", "nether").equals(location)) {
                return Level.NETHER;
            }

            if (ResourceLocation.fromNamespaceAndPath("minecraft", "end").equals(location)) {
                return Level.END;
            }

            return ResourceKey.create(Registries.DIMENSION, location);
        } catch (RuntimeException exception) {
            RegeneratingOreVeins.LOGGER.warn("Invalid dimension '{}', defaulting to minecraft:overworld", id, exception);
            return Level.OVERWORLD;
        }
    }

    private static List<ResourceKey<Level>> parseDimensions(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return List.of(Level.OVERWORLD);
        }

        Set<ResourceKey<Level>> dimensions = new LinkedHashSet<>();
        if (element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                if (entry.isJsonPrimitive()) {
                    dimensions.add(parseDimension(entry.getAsString()));
                }
            }
        } else if (element.isJsonPrimitive()) {
            dimensions.add(parseDimension(element.getAsString()));
        }

        return dimensions.isEmpty() ? List.of(Level.OVERWORLD) : new ArrayList<>(dimensions);
    }

    private static List<BiomeCriterion> parseBiomes(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }

        List<BiomeCriterion> biomes = new ArrayList<>();
        if (element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                if (entry.isJsonPrimitive()) {
                    parseBiome(entry.getAsString()).ifPresent(biomes::add);
                }
            }
        } else if (element.isJsonPrimitive()) {
            parseBiome(element.getAsString()).ifPresent(biomes::add);
        }

        return biomes;
    }

    private static Optional<BiomeCriterion> parseBiome(String id) {
        try {
            if (id.startsWith("#")) {
                return Optional.of(BiomeCriterion.tag(TagKey.create(Registries.BIOME, ResourceLocation.parse(id.substring(1)))));
            }

            return Optional.of(BiomeCriterion.biome(ResourceKey.create(Registries.BIOME, ResourceLocation.parse(id))));
        } catch (RuntimeException exception) {
            RegeneratingOreVeins.LOGGER.warn("Invalid biome selector '{}'", id, exception);
            return Optional.empty();
        }
    }

    private static String getString(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }

    private static double getDouble(JsonObject object, String key, double fallback) {
        return object.has(key) ? object.get(key).getAsDouble() : fallback;
    }

    private static double clampUnit(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static void writeDefaultAreas() throws IOException {
        JsonArray array = new JsonArray();
        JsonObject area = new JsonObject();
        area.addProperty("dimension", "minecraft:overworld");
        area.addProperty("name", "frontier_0");
        area.addProperty("type", "box");
        area.addProperty("x", 0);
        area.addProperty("y", 128);
        area.addProperty("z", 0);
        area.addProperty("dimx", 8192);
        area.addProperty("dimy", 512);
        area.addProperty("dimz", 8192);
        array.add(area);
        try (Writer writer = Files.newBufferedWriter(AREAS_PATH)) {
            GSON.toJson(array, writer);
        }
    }

    private static void writeDefaultVeins() throws IOException {
        JsonArray array = new JsonArray();
        array.add(defaultVein("andesite", "minecraft:andesite", "minecraft:overworld", "box", 26, 42, 2, -16, 96, 8, 1800, -120, 120));
        array.add(defaultVein("coal_ore", "minecraft:coal_ore", "minecraft:overworld", "circle", 20, 36, 2, 0, 192, 7, 2400, -180, 180));
        array.add(defaultVein("iron_ore", "minecraft:iron_ore", "minecraft:overworld", "circle", 18, 32, 2, -24, 80, 7, 2700, -180, 180));
        array.add(defaultVein("gold_ore", "minecraft:gold_ore", "minecraft:overworld", "circle", 12, 24, 1, -64, 32, 9, 3600, -240, 240));
        array.add(defaultVein("budding_amethyst", "minecraft:budding_amethyst", "minecraft:overworld", "box", 4, 10, 1, -64, 30, 18, 7200, -600, 600));
        array.add(defaultVein("diamond_ore", "minecraft:diamond_ore", "minecraft:overworld", "circle", 8, 18, 1, -64, 16, 10, 5400, -300, 300));
        array.add(defaultVein("emerald_ore", "minecraft:emerald_ore", "minecraft:overworld", "circle", 4, 10, 1, -16, 320, 12, 5400, -300, 300));
        array.add(defaultVein("lapis_lazuli_ore", "minecraft:lapis_ore", "minecraft:overworld", "circle", 10, 22, 1, -64, 64, 9, 3600, -240, 240));
        array.add(defaultVein("redstone_ore", "minecraft:redstone_ore", "minecraft:overworld", "circle", 12, 26, 1, -64, 16, 8, 3600, -240, 240));
        array.add(defaultVein("quartz_ore", "minecraft:quartz_block", "minecraft:overworld", "circle", 8, 18, 1, 0, 96, 12, 3600, -240, 240));
        array.add(defaultVein("netherite_ore", "minecraft:ancient_debris", "minecraft:the_nether", "circle", 3, 8, 1, 8, 22, 18, 10800, -900, 900));
        array.add(defaultVein("nether_quartz_ore", "minecraft:nether_quartz_ore", "minecraft:the_nether", "circle", 18, 34, 2, 10, 117, 7, 2700, -180, 180));
        array.add(defaultUnusualGoldVein());

        try (Writer writer = Files.newBufferedWriter(VEINS_PATH)) {
            GSON.toJson(array, writer);
        }
    }

    private static JsonObject defaultVein(
            String id,
            String block,
            String dimension,
            String shape,
            int minSize,
            int maxSize,
            int attempts,
            int minY,
            int maxY,
            int separation,
            int regenerationIntervalSeconds,
            int jitterRangeMin,
            int jitterRangeMax
    ) {
        JsonObject vein = new JsonObject();
        vein.addProperty("id", id);
        JsonArray blocks = new JsonArray();
        blocks.add(block);
        vein.add("blocks", blocks);
        vein.addProperty("dimension", dimension);
        vein.addProperty("shape", shape);
        vein.addProperty("min_size", minSize);
        vein.addProperty("max_size", maxSize);
        vein.addProperty("attempts", attempts);
        vein.addProperty("min_y", minY);
        vein.addProperty("max_y", maxY);
        vein.addProperty("chunk_minimum_generation_separation", separation);
        vein.addProperty("regeneration_interval_seconds", regenerationIntervalSeconds);
        JsonObject jitter = new JsonObject();
        jitter.addProperty("range_min", jitterRangeMin);
        jitter.addProperty("range_max", jitterRangeMax);
        vein.add("regeneration_interval_jitter", jitter);
        return vein;
    }

    private static JsonObject defaultUnusualGoldVein() {
        JsonObject vein = new JsonObject();
        vein.addProperty("id", "gold_ore_unusual");

        JsonArray blocks = new JsonArray();
        blocks.add("minecraft:gold_ore");
        blocks.add("minecraft:deepslate_gold_ore");
        blocks.add("minecraft:nether_gold_ore");
        vein.add("blocks", blocks);

        JsonArray weights = new JsonArray();
        weights.add(1);
        weights.add(2);
        weights.add(4);
        vein.add("weights", weights);

        JsonArray dimensions = new JsonArray();
        dimensions.add("minecraft:overworld");
        dimensions.add("minecraft:the_nether");
        vein.add("dimension", dimensions);

        JsonArray biomes = new JsonArray();
        biomes.add("minecraft:badlands");
        biomes.add("#c:is_jungle");
        biomes.add("minecraft:nether_wastes");
        vein.add("biome", biomes);

        vein.addProperty("shape", "circle");
        vein.addProperty("min_size", 12);
        vein.addProperty("max_size", 24);
        vein.addProperty("fill_factor", 1.0D);
        vein.addProperty("attempts", 1);
        vein.addProperty("min_y", -64);
        vein.addProperty("max_y", 32);
        vein.addProperty("chunk_minimum_generation_separation", 0.5D);
        vein.addProperty("regeneration_interval_seconds", 3600);
        JsonObject jitter = new JsonObject();
        jitter.addProperty("range_min", -240);
        jitter.addProperty("range_max", 240);
        vein.add("regeneration_interval_jitter", jitter);
        return vein;
    }

    public enum VeinShape {
        CIRCLE,
        BOX;

        public static VeinShape fromName(String name) {
            return "box".equalsIgnoreCase(name) ? BOX : CIRCLE;
        }
    }

    public record LoadedConfig(Map<String, AreaDefinition> areasByName, Map<String, VeinDefinition> veinsById) {
        public static LoadedConfig empty() {
            return new LoadedConfig(Map.of(), Map.of());
        }
    }

    public record AreaDefinition(
            String name,
            ResourceKey<Level> dimension,
            int x,
            int y,
            int z,
            int dimX,
            int dimY,
            int dimZ
    ) {
        public boolean contains(BlockPos pos) {
            int halfX = this.dimX / 2;
            int halfY = this.dimY / 2;
            int halfZ = this.dimZ / 2;
            return pos.getX() >= this.x - halfX
                    && pos.getX() <= this.x + halfX
                    && pos.getY() >= this.y - halfY
                    && pos.getY() <= this.y + halfY
                    && pos.getZ() >= this.z - halfZ
                    && pos.getZ() <= this.z + halfZ;
        }
    }

    public record RegenerationIntervalJitter(int rangeMin, int rangeMax) {
        public static final RegenerationIntervalJitter NONE = new RegenerationIntervalJitter(0, 0);
    }

    public record BiomeCriterion(ResourceKey<Biome> biome, TagKey<Biome> tag) {
        public static BiomeCriterion biome(ResourceKey<Biome> biome) {
            return new BiomeCriterion(Objects.requireNonNull(biome, "biome"), null);
        }

        public static BiomeCriterion tag(TagKey<Biome> tag) {
            return new BiomeCriterion(null, Objects.requireNonNull(tag, "tag"));
        }

        public boolean matches(Holder<Biome> holder) {
            return this.tag != null ? holder.is(this.tag) : holder.is(this.biome);
        }

        public boolean isTag() {
            return this.tag != null;
        }

        public String asString() {
            return this.tag != null ? "#" + this.tag.location() : this.biome.location().toString();
        }
    }

    public record VeinDefinition(
            String id,
            List<ResourceKey<Level>> dimensions,
            List<BiomeCriterion> biomes,
            List<BlockState> blocks,
            List<Integer> weights,
            VeinShape shape,
            int minSize,
            int maxSize,
            int attempts,
            int minY,
            int maxY,
            double chunkMinimumGenerationSeparation,
            double fillFactor,
            int regenerationIntervalSeconds,
            RegenerationIntervalJitter regenerationIntervalJitter,
            String areaName,
            AreaDefinition area
    ) {
        public VeinDefinition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(dimensions, "dimensions");
            Objects.requireNonNull(biomes, "biomes");
            Objects.requireNonNull(blocks, "blocks");
            Objects.requireNonNull(weights, "weights");
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(regenerationIntervalJitter, "regenerationIntervalJitter");
        }

        public boolean matchesDimension(ResourceKey<Level> dimension) {
            return this.dimensions.contains(dimension);
        }

        public boolean matchesBiome(Holder<Biome> biome) {
            if (this.biomes.isEmpty()) {
                return true;
            }

            for (BiomeCriterion criterion : this.biomes) {
                if (criterion.matches(biome)) {
                    return true;
                }
            }

            return false;
        }

        public ResourceKey<Level> primaryDimension() {
            return this.dimensions.isEmpty() ? Level.OVERWORLD : this.dimensions.getFirst();
        }

        public int pickShapeSize(RandomSource random) {
            int min = Math.min(this.minSize, this.maxSize);
            int max = Math.max(this.minSize, this.maxSize);
            return min == max ? min : Mth.nextInt(random, min, max);
        }

        public BlockState pickBlockState(RandomSource random) {
            int total = this.weights.stream().mapToInt(Integer::intValue).sum();
            if (total <= 0) {
                return this.blocks.getFirst();
            }

            int roll = random.nextInt(total);
            int cursor = 0;
            for (int i = 0; i < this.blocks.size(); i++) {
                cursor += this.weights.get(Math.min(i, this.weights.size() - 1));
                if (roll < cursor) {
                    return this.blocks.get(i);
                }
            }

            return this.blocks.getLast();
        }

        public int estimatedRadius() {
            int blockCount = Math.max(this.minSize, this.maxSize);
            return Math.max(1, (int) Math.ceil(Math.cbrt(blockCount)) + 1);
        }

        public ResourceLocation structureLocation() {
            return ResourceLocation.fromNamespaceAndPath(RegeneratingOreVeins.MOD_ID, this.id);
        }
    }
}
