package net.stargazer.regenerating_ore_veins;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.DetectedVersion;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class GeneratedVeinPackResources extends AbstractPackResources {
    private final PackMetadataSection metadata = new PackMetadataSection(
            Component.literal("Generated structures for Regenerating Ore Veins"),
            DetectedVersion.BUILT_IN.getPackVersion(PackType.SERVER_DATA)
    );
    private final Map<ResourceLocation, byte[]> resources = new HashMap<>();

    public GeneratedVeinPackResources(PackLocationInfo location) {
        super(location);
        VeinConfig.LoadedConfig config = VeinConfig.loadFromDisk();
        for (VeinConfig.VeinDefinition vein : config.veinsById().values()) {
            ResourceLocation locationId = ResourceLocation.fromNamespaceAndPath(RegeneratingOreVeins.MOD_ID, "worldgen/structure/" + vein.id() + ".json");
            this.resources.put(locationId, buildStructureJson(vein).getBytes(StandardCharsets.UTF_8));
        }

        VeinLocatorConfig.Values locatorConfig = VeinLocatorConfig.loadFromDisk();
        if (locatorConfig.allowCrafting()) {
            this.resources.put(
                    ResourceLocation.fromNamespaceAndPath(RegeneratingOreVeins.MOD_ID, "recipe/vein_locator.json"),
                    buildVeinLocatorRecipeJson(locatorConfig).getBytes(StandardCharsets.UTF_8)
            );
        }

        this.resources.put(
                ResourceLocation.fromNamespaceAndPath(RegeneratingOreVeins.MOD_ID, "recipe/vein_locator_tuning.json"),
                buildVeinLocatorTuningRecipeJson().getBytes(StandardCharsets.UTF_8)
        );
    }

    public static Pack createPack() {
        PackLocationInfo info = new PackLocationInfo(
                "mod/" + RegeneratingOreVeins.MOD_ID + "/generated_veins",
                Component.literal("Regenerating Ore Veins Structures"),
                PackSource.BUILT_IN,
                Optional.empty()
        );
        return Pack.readMetaAndCreate(info, new Supplier(), PackType.SERVER_DATA, new net.minecraft.server.packs.PackSelectionConfig(true, Pack.Position.TOP, false));
    }

    @Override
    public void close() {
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.SERVER_DATA ? Set.of(RegeneratingOreVeins.MOD_ID) : Set.of();
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String... elements) {
        return null;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != PackType.SERVER_DATA) {
            return null;
        }

        byte[] data = this.resources.get(location);
        return data == null ? null : () -> new ByteArrayInputStream(data);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput resourceOutput) {
        if (type != PackType.SERVER_DATA || !RegeneratingOreVeins.MOD_ID.equals(namespace)) {
            return;
        }

        this.resources.forEach((location, bytes) -> {
            if (location.getPath().startsWith(path)) {
                resourceOutput.accept(location, () -> new ByteArrayInputStream(bytes));
            }
        });
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
        return serializer.getMetadataSectionName().equals("pack") ? (T) this.metadata : null;
    }

    private static String buildStructureJson(VeinConfig.VeinDefinition vein) {
        return """
                {
                  "type": "%s:vein",
                  "config_id": "%s",
                  "biomes": %s,
                  "spawn_overrides": {},
                  "step": "underground_ores"
                }
                """.formatted(RegeneratingOreVeins.MOD_ID, vein.id(), biomeSelectorJson(vein));
    }

    private static String buildVeinLocatorRecipeJson(VeinLocatorConfig.Values config) {
        if (config.craftAnyOrder()) {
            String ingredients = config.craftingRecipe()
                    .stream()
                    .map(location -> "    { \"item\": " + quote(location.toString()) + " }")
                    .collect(java.util.stream.Collectors.joining(",\n"));
            return """
                    {
                      "type": "minecraft:crafting_shapeless",
                      "category": "misc",
                      "ingredients": [
                    %s
                      ],
                      "result": {
                        "id": "%s:vein_locator",
                        "count": 1
                      }
                    }
                    """.formatted(ingredients, RegeneratingOreVeins.MOD_ID);
        }

        java.util.List<String> patternRows = new java.util.ArrayList<>();
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < config.craftingRecipe().size(); i++) {
            char key = (char) ('A' + i);
            int row = i / 3;
            if (patternRows.size() <= row) {
                patternRows.add("");
            }

            patternRows.set(row, patternRows.get(row) + key);
            if (i > 0) {
                keys.append(",\n");
            }

            keys.append("    ")
                    .append(quote(String.valueOf(key)))
                    .append(": { \"item\": ")
                    .append(quote(config.craftingRecipe().get(i).toString()))
                    .append(" }");
        }

        String pattern = patternRows.stream()
                .map(row -> "    " + quote(row))
                .collect(java.util.stream.Collectors.joining(",\n"));
        return """
                {
                  "type": "minecraft:crafting_shaped",
                  "category": "misc",
                  "pattern": [
                %s
                  ],
                  "key": {
                %s
                  },
                  "result": {
                    "id": "%s:vein_locator",
                    "count": 1
                  }
                }
                """.formatted(pattern, keys, RegeneratingOreVeins.MOD_ID);
    }

    private static String buildVeinLocatorTuningRecipeJson() {
        return """
                {
                  "type": "%s:vein_locator_tuning",
                  "category": "misc"
                }
                """.formatted(RegeneratingOreVeins.MOD_ID);
    }

    private static String biomeSelectorJson(VeinConfig.VeinDefinition vein) {
        List<VeinConfig.BiomeCriterion> biomes = vein.biomeBlacklist().isEmpty() ? vein.biomeWhitelist() : List.of();
        if (biomes.size() == 1 && biomes.getFirst().namespace() == null) {
            return quote(biomes.getFirst().asString());
        }

        if (biomes.size() > 1 && biomes.stream().noneMatch(VeinConfig.BiomeCriterion::isTag) && biomes.stream().noneMatch(criterion -> criterion.namespace() != null)) {
            return "[" + biomes.stream().map(criterion -> quote(criterion.asString())).collect(java.util.stream.Collectors.joining(", ")) + "]";
        }

        return quote(dimensionBiomeSelector(vein));
    }

    private static String dimensionBiomeSelector(VeinConfig.VeinDefinition vein) {
        if (Level.NETHER.equals(vein.primaryDimension())) {
            return "#minecraft:is_nether";
        }

        if (Level.END.equals(vein.primaryDimension())) {
            return "#minecraft:is_end";
        }

        return "#minecraft:is_overworld";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static final class Supplier implements Pack.ResourcesSupplier {
        @Override
        public PackResources openPrimary(PackLocationInfo location) {
            return new GeneratedVeinPackResources(location);
        }

        @Override
        public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
            return openPrimary(location);
        }
    }
}
