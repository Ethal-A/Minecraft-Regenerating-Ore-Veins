package net.stargazer.regenerating_ore_veins;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = RegeneratingOreVeins.MOD_ID)
public final class VeinRuntime {
    private static final int ACTIVE_ENTRY_VALIDATION_BUDGET = 128;
    private static final Map<ServerLevel, LongSet> PENDING_GENERATION = new HashMap<>();
    private static final Map<ServerLevel, LongSet> PENDING_REGENERATOR_REPLACEMENT = new HashMap<>();
    private static final Map<ServerLevel, Integer> ACTIVE_ENTRY_VALIDATION_CURSOR = new HashMap<>();

    private VeinRuntime() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("regenerating_ore_veins")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("reload").executes(VeinRuntime::reloadCommand))
                        .then(Commands.literal("place")
                                .then(Commands.argument("id", StringArgumentType.word()).executes(VeinRuntime::placeCommand)))
        );
        event.getDispatcher().register(
                Commands.literal("rov")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("reload").executes(VeinRuntime::reloadCommand))
                        .then(Commands.literal("place")
                                .then(Commands.argument("id", StringArgumentType.word()).executes(VeinRuntime::placeCommand)))
        );
    }

    public static void onAddPackFinders(AddPackFindersEvent event) {
        try {
            if (event.getPackType() != net.minecraft.server.packs.PackType.SERVER_DATA) {
                return;
            }

            VeinConfig.ensureDefaults();
            event.addRepositorySource(consumer -> {
                var pack = GeneratedVeinPackResources.createPack();
                if (pack != null) {
                    consumer.accept(pack);
                }
            });
        } catch (RuntimeException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to add generated vein datapack", exception);
        }
    }

    private static int reloadCommand(CommandContext<CommandSourceStack> context) {
        GlobalConfig.loadFromDisk();
        VeinConfig.LoadedConfig config = VeinConfig.loadFromDisk();
        context.getSource().sendSuccess(
                () -> Component.literal("Reloaded Regenerating Ore Veins config: " + config.veinsById().size() + " veins, " + config.areasByName().size() + " areas."),
                true
        );
        return config.veinsById().size();
    }

    private static int placeCommand(CommandContext<CommandSourceStack> context) {
        String id = StringArgumentType.getString(context, "id");
        ServerLevel level = context.getSource().getLevel();
        BlockPos pos = BlockPos.containing(context.getSource().getPosition());
        VeinConfig.VeinDefinition vein = VeinConfig.getVein(id).orElse(null);
        if (vein == null) {
            context.getSource().sendFailure(Component.literal("Unknown Regenerating Ore Veins vein id: " + id));
            return 0;
        }

        int placed = placeManagedVein(level, pos, vein, level.random, true);
        context.getSource().sendSuccess(
                () -> Component.literal("Placed vein '" + id + "' at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " with " + placed + " blocks."),
                true
        );
        return placed;
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        try {
            if (!event.isNewChunk() || !(event.getLevel() instanceof ServerLevel serverLevel)) {
                return;
            }

            PENDING_GENERATION.computeIfAbsent(serverLevel, ignored -> new LongLinkedOpenHashSet()).add(event.getChunk().getPos().toLong());
        } catch (RuntimeException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to queue vein generation for loaded chunk", exception);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        try {
            Player player = event.getEntity();
            if (!player.isShiftKeyDown() || event.getHand() != InteractionHand.MAIN_HAND) {
                return;
            }

            BlockPos pos = event.getPos();
            BlockState state = event.getLevel().getBlockState(pos);
            if (!state.is(ModContent.REGENERATOR_BLOCK.get())) {
                return;
            }

            if (!RegeneratorBlock.shouldInspectWithHeldStack(player.getItemInHand(event.getHand()), event.getLevel(), pos, player)) {
                return;
            }

            event.setUseBlock(TriState.TRUE);
            event.setUseItem(TriState.FALSE);
        } catch (RuntimeException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to process regenerator block right click", exception);
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        try {
            if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
                return;
            }

            processPendingChunkGeneration(serverLevel);
            validateActiveEntries(serverLevel);
            processPendingRegeneratorReplacement(serverLevel);
        } catch (RuntimeException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to process regenerating ore vein tick", exception);
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        try {
            if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
                return;
            }

            BlockPos pos = event.getPos();
            BlockState state = event.getState();
            Player player = event.getPlayer();
            VeinSavedData savedData = VeinSavedData.get(serverLevel);

            if (state.is(ModContent.REGENERATOR_BLOCK.get())) {
                if (player != null && player.isCreative()) {
                    savedData.removeEntry(pos);
                    return;
                }

                if (!GlobalConfig.get().allowBreaking() || player == null || !player.isShiftKeyDown()) {
                    event.setCanceled(true);
                    return;
                }

                savedData.removeEntry(pos);
                return;
            }

            VeinSavedData.VeinEntry entry = savedData.getEntry(pos);
            if (entry == null || entry.lastMinedEpochSecond() != VeinSavedData.ACTIVE_LAST_MINED) {
                return;
            }

            savedData.markMined(pos, epochSeconds());
            queueRegeneratorReplacement(serverLevel, pos);
        } catch (RuntimeException exception) {
            event.setCanceled(true);
            RegeneratingOreVeins.LOGGER.error("Failed to process regenerating ore vein block break", exception);
        }
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        try {
            if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
                return;
            }

            GlobalConfig.Values config = GlobalConfig.get();
            VeinSavedData savedData = VeinSavedData.get(serverLevel);
            List<BlockPos> affectedBlocks = event.getAffectedBlocks();
            List<BlockPos> snapshot = new ArrayList<>(affectedBlocks);

            for (BlockPos pos : snapshot) {
                BlockState state = serverLevel.getBlockState(pos);
                if (state.is(ModContent.REGENERATOR_BLOCK.get())) {
                    if (config.destroyedByExplosives()) {
                        savedData.removeEntry(pos);
                    } else {
                        affectedBlocks.remove(pos);
                    }

                    continue;
                }

                VeinSavedData.VeinEntry entry = savedData.getEntry(pos);
                if (entry != null && entry.lastMinedEpochSecond() == VeinSavedData.ACTIVE_LAST_MINED) {
                    savedData.markMined(pos, epochSeconds());
                    queueRegeneratorReplacement(serverLevel, pos);
                }
            }
        } catch (RuntimeException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to process regenerating ore vein explosion", exception);
        }
    }

    public static int placeManagedVein(ServerLevel level, BlockPos center, VeinConfig.VeinDefinition vein, RandomSource random, boolean forceReplace) {
        VeinSavedData savedData = VeinSavedData.get(level);
        List<BlockPos> positions = computeShapePositions(center, vein, random);
        int placed = 0;

        for (BlockPos pos : positions) {
            if (!level.isInWorldBounds(pos)) {
                continue;
            }

            if (!forceReplace && vein.area() != null && !vein.area().contains(pos)) {
                continue;
            }

            BlockState existingState = level.getBlockState(pos);
            if (!forceReplace && !canReplaceNatural(existingState)) {
                continue;
            }

            BlockState targetState = vein.pickBlockState(random);
            try {
                level.setBlock(pos, targetState, 3);
            } catch (RuntimeException exception) {
                RegeneratingOreVeins.LOGGER.error("Failed to place managed vein block at {}", pos, exception);
                continue;
            }

            savedData.putEntry(
                    pos,
                    new VeinSavedData.VeinEntry(
                            targetState,
                            vein.regenerationIntervalSeconds(),
                            vein.regenerationIntervalSeconds(),
                            vein.regenerationIntervalJitter().rangeMin(),
                            vein.regenerationIntervalJitter().rangeMax(),
                            VeinSavedData.ACTIVE_LAST_MINED
                    )
            );
            placed++;
        }

        return placed;
    }

    public static void spawnRegenerationSmoke(ServerLevel level, BlockPos pos) {
        if (!GlobalConfig.get().regenerationSmokeParticles()) {
            return;
        }

        int particles = GlobalConfig.DEFAULT_SMOKE_PARTICLE_COUNT;
        for (int i = 0; i < particles; i++) {
            double x = pos.getX() + 0.25D + level.random.nextDouble() * 0.5D;
            double y = pos.getY() + 0.2D + level.random.nextDouble() * 0.6D;
            double z = pos.getZ() + 0.25D + level.random.nextDouble() * 0.5D;
            level.sendParticles(ParticleTypes.SMOKE, x, y, z, 1, 0.0D, 0.02D, 0.0D, 0.01D);
        }
    }

    public static String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return "%dh %02dm %02ds".formatted(hours, minutes, seconds);
    }

    public static int applyIntervalJitter(int intervalSeconds, int rangeMin, int rangeMax, RandomSource random) {
        int min = Math.min(rangeMin, rangeMax);
        int max = Math.max(rangeMin, rangeMax);
        if (min == 0 && max == 0) {
            return Math.max(1, intervalSeconds);
        }

        RandomSource randomSource = random == null ? RandomSource.create() : random;
        return Math.max(1, intervalSeconds + Mth.nextInt(randomSource, min, max));
    }

    private static void processPendingChunkGeneration(ServerLevel level) {
        LongSet pending = PENDING_GENERATION.get(level);
        if (pending == null || pending.isEmpty()) {
            return;
        }

        VeinConfig.LoadedConfig config = VeinConfig.loadFromDisk();
        VeinSavedData savedData = VeinSavedData.get(level);
        List<Long> toProcess = new ArrayList<>();

        long[] chunks = pending.toLongArray();
        for (int i = 0; i < chunks.length && toProcess.size() < 8; i++) {
            long chunkLong = chunks[i];
            ChunkPos chunkPos = new ChunkPos(chunkLong);
            if (savedData.isChunkGenerated(chunkLong)) {
                toProcess.add(chunkLong);
                continue;
            }

            if (!level.getChunkSource().hasChunk(chunkPos.x, chunkPos.z)) {
                continue;
            }

            generateChunk(level, chunkPos, config, savedData);
            savedData.markChunkGenerated(chunkLong);
            toProcess.add(chunkLong);
        }

        for (long processed : toProcess) {
            pending.remove(processed);
        }
    }

    private static void validateActiveEntries(ServerLevel level) {
        VeinSavedData savedData = VeinSavedData.get(level);
        long[] positions = savedData.entryPositions();
        if (positions.length == 0) {
            ACTIVE_ENTRY_VALIDATION_CURSOR.remove(level);
            return;
        }

        int cursor = Math.floorMod(ACTIVE_ENTRY_VALIDATION_CURSOR.getOrDefault(level, 0), positions.length);
        int checked = 0;
        while (checked < ACTIVE_ENTRY_VALIDATION_BUDGET && checked < positions.length) {
            long packedPos = positions[cursor];
            BlockPos pos = BlockPos.of(packedPos);
            cursor = (cursor + 1) % positions.length;
            checked++;

            if (!level.hasChunkAt(pos)) {
                continue;
            }

            VeinSavedData.VeinEntry entry = savedData.getEntry(pos);
            if (entry == null || entry.lastMinedEpochSecond() != VeinSavedData.ACTIVE_LAST_MINED) {
                continue;
            }

            BlockState currentState = level.getBlockState(pos);
            if (currentState.equals(entry.targetState()) || currentState.is(ModContent.REGENERATOR_BLOCK.get())) {
                continue;
            }

            savedData.markMined(pos, epochSeconds());
            queueRegeneratorReplacement(level, pos);
        }

        ACTIVE_ENTRY_VALIDATION_CURSOR.put(level, cursor);
    }

    private static void processPendingRegeneratorReplacement(ServerLevel level) {
        LongSet pending = PENDING_REGENERATOR_REPLACEMENT.get(level);
        if (pending == null || pending.isEmpty()) {
            return;
        }

        VeinSavedData savedData = VeinSavedData.get(level);
        List<Long> completed = new ArrayList<>();
        long[] positions = pending.toLongArray();

        for (int i = 0; i < positions.length && i < 64; i++) {
            BlockPos pos = BlockPos.of(positions[i]);
            VeinSavedData.VeinEntry entry = savedData.getEntry(pos);
            if (entry == null) {
                completed.add(positions[i]);
                continue;
            }

            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.canBeReplaced()) {
                continue;
            }

            try {
                level.setBlock(pos, ModContent.REGENERATOR_BLOCK.get().defaultBlockState(), 3);
            } catch (RuntimeException exception) {
                RegeneratingOreVeins.LOGGER.error("Failed to place regenerator block at {}", pos, exception);
                continue;
            }

            if (level.getBlockEntity(pos) instanceof RegeneratorBlockEntity blockEntity) {
                blockEntity.configureFromEntry(entry);
            }

            completed.add(positions[i]);
        }

        for (long completedPos : completed) {
            pending.remove(completedPos);
        }
    }

    private static void generateChunk(ServerLevel level, ChunkPos chunkPos, VeinConfig.LoadedConfig config, VeinSavedData savedData) {
        for (VeinConfig.VeinDefinition vein : config.veinsById().values()) {
            if (!vein.matchesDimension(level.dimension())) {
                continue;
            }

            if (!isCandidateChunk(level.getSeed(), chunkPos, vein)) {
                continue;
            }

            RandomSource random = RandomSource.create(mixSeed(level.getSeed(), chunkPos, vein.id()));
            int totalAttempts = Math.max(1, vein.attempts()) * generationMultiplier(vein);
            for (int attempt = 0; attempt < totalAttempts; attempt++) {
                int x = chunkPos.getMinBlockX() + random.nextInt(16);
                int z = chunkPos.getMinBlockZ() + random.nextInt(16);
                int y = Mth.nextInt(random, Math.min(vein.minY(), vein.maxY()), Math.max(vein.minY(), vein.maxY()));
                BlockPos center = new BlockPos(x, y, z);
                if (vein.area() != null && !vein.area().contains(center)) {
                    continue;
                }

                if (!vein.matchesBiome(level.getBiome(center))) {
                    continue;
                }

                placeManagedVein(level, center, vein, random, false);
            }
        }
    }

    private static boolean isCandidateChunk(long seed, ChunkPos chunkPos, VeinConfig.VeinDefinition vein) {
        if (vein.chunkMinimumGenerationSeparation() < 1.0D) {
            return true;
        }

        int spacing = Math.max(1, Mth.ceil(vein.chunkMinimumGenerationSeparation()));
        int regionX = Math.floorDiv(chunkPos.x, spacing);
        int regionZ = Math.floorDiv(chunkPos.z, spacing);
        RandomSource random = RandomSource.create(seed ^ ((long) regionX * 341873128712L) ^ ((long) regionZ * 132897987541L) ^ vein.id().hashCode());
        int candidateX = regionX * spacing + random.nextInt(spacing);
        int candidateZ = regionZ * spacing + random.nextInt(spacing);
        return chunkPos.x == candidateX && chunkPos.z == candidateZ;
    }

    private static int generationMultiplier(VeinConfig.VeinDefinition vein) {
        double separation = vein.chunkMinimumGenerationSeparation();
        if (separation >= 1.0D) {
            return 1;
        }

        return Math.max(1, (int) Math.round(1.0D / Math.max(0.01D, separation)));
    }

    private static long mixSeed(long worldSeed, ChunkPos chunkPos, String id) {
        long hash = 1125899906842597L;
        for (int i = 0; i < id.length(); i++) {
            hash = 31L * hash + id.charAt(i);
        }

        return worldSeed ^ (chunkPos.x * 341873128712L) ^ (chunkPos.z * 132897987541L) ^ hash;
    }

    private static List<BlockPos> computeShapePositions(BlockPos center, VeinConfig.VeinDefinition vein, RandomSource random) {
        int shapeSize = vein.pickShapeSize(random);
        List<BlockPos> positions = new ArrayList<>();

        if (vein.shape() == VeinConfig.VeinShape.BOX) {
            int root = Math.max(1, (int) Math.round(Math.cbrt(shapeSize)));
            int halfX = Math.max(1, Mth.ceil(root / 2.0D));
            int halfY = Math.max(1, Mth.ceil(root / 3.0D));
            int halfZ = Math.max(1, halfX);
            for (int x = -halfX; x <= halfX; x++) {
                for (int y = -halfY; y <= halfY; y++) {
                    for (int z = -halfZ; z <= halfZ; z++) {
                        positions.add(center.offset(x, y, z));
                    }
                }
            }
        } else {
            int radius = sphereRadiusForSize(shapeSize);
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        double distance = (x * x + y * y + z * z) / (double) (radius * radius);
                        if (distance <= 1.0D) {
                            positions.add(center.offset(x, y, z));
                        }
                    }
                }
            }
        }

        if (vein.fillFactor() >= 1.0D) {
            return positions;
        }

        if (vein.fillFactor() <= 0.0D) {
            return List.of();
        }

        List<BlockPos> filled = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (random.nextDouble() <= vein.fillFactor()) {
                filled.add(pos);
            }
        }

        return filled;
    }

    private static int sphereRadiusForSize(int shapeSize) {
        double radius = Math.cbrt((3.0D * Math.max(1, shapeSize)) / (4.0D * Math.PI));
        return Math.max(1, (int) Math.round(radius));
    }

    private static boolean canReplaceNatural(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.STONE_ORE_REPLACEABLES)
                || state.is(net.minecraft.tags.BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || state.is(net.minecraft.tags.BlockTags.BASE_STONE_NETHER)
                || state.is(Blocks.END_STONE);
    }

    public static void queueRegeneratorReplacement(ServerLevel level, BlockPos pos) {
        PENDING_REGENERATOR_REPLACEMENT.computeIfAbsent(level, ignored -> new LongLinkedOpenHashSet()).add(pos.asLong());
    }

    private static long epochSeconds() {
        return TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
    }
}
