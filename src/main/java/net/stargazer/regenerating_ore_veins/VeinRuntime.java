package net.stargazer.regenerating_ore_veins;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
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
                        .then(updateExistingCommandTree())
        );
        event.getDispatcher().register(
                Commands.literal("rov")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("reload").executes(VeinRuntime::reloadCommand))
                        .then(Commands.literal("place")
                                .then(Commands.argument("id", StringArgumentType.word()).executes(VeinRuntime::placeCommand)))
                        .then(updateExistingCommandTree())
        );
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> updateExistingCommandTree() {
        return Commands.literal("update_existing")
                .executes(context -> updateExistingCommand(context, "", "", false))
                .then(updateExistingModeTree("regenerate", true))
                .then(Commands.literal("id")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> updateExistingCommand(context, StringArgumentType.getString(context, "id"), "", false))
                                .then(Commands.literal("area")
                                        .then(Commands.argument("area", StringArgumentType.word())
                                                .executes(context -> updateExistingCommand(context, StringArgumentType.getString(context, "id"), StringArgumentType.getString(context, "area"), false))))))
                .then(Commands.literal("area")
                        .then(Commands.argument("area", StringArgumentType.word())
                                .executes(context -> updateExistingCommand(context, "", StringArgumentType.getString(context, "area"), false))
                                .then(Commands.literal("id")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .executes(context -> updateExistingCommand(context, StringArgumentType.getString(context, "id"), StringArgumentType.getString(context, "area"), false))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> updateExistingModeTree(String name, boolean regenerate) {
        return Commands.literal(name)
                .executes(context -> updateExistingCommand(context, "", "", regenerate))
                .then(Commands.literal("id")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> updateExistingCommand(context, StringArgumentType.getString(context, "id"), "", regenerate))
                                .then(Commands.literal("area")
                                        .then(Commands.argument("area", StringArgumentType.word())
                                                .executes(context -> updateExistingCommand(context, StringArgumentType.getString(context, "id"), StringArgumentType.getString(context, "area"), regenerate))))))
                .then(Commands.literal("area")
                        .then(Commands.argument("area", StringArgumentType.word())
                                .executes(context -> updateExistingCommand(context, "", StringArgumentType.getString(context, "area"), regenerate))
                                .then(Commands.literal("id")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .executes(context -> updateExistingCommand(context, StringArgumentType.getString(context, "id"), StringArgumentType.getString(context, "area"), regenerate))))));
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
        VeinLocatorConfig.loadFromDisk();
        VeinConfig.LoadedConfig config = VeinConfig.loadFromDisk();
        context.getSource().sendSuccess(
                () -> Component.literal("Reloaded Regenerating Ore Veins config: " + config.veinsById().size() + " veins, " + config.areasByName().size() + " areas."),
                true
        );
        sendConfigReport(context.getSource(), List.of(GlobalConfig.lastReport(), VeinLocatorConfig.lastReport(), config.report()));
        return config.veinsById().size();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            GlobalConfig.loadFromDisk();
            VeinLocatorConfig.loadFromDisk();
            VeinConfig.LoadedConfig config = VeinConfig.loadFromDisk();
            sendConfigReport(player, List.of(GlobalConfig.lastReport(), VeinLocatorConfig.lastReport(), config.report()));
        }
    }

    private static void sendConfigReport(CommandSourceStack source, List<VeinConfig.ConfigReport> reports) {
        int errors = reports.stream().mapToInt(report -> report.errors().size()).sum();
        int warnings = reports.stream().mapToInt(report -> report.warnings().size()).sum();
        if (errors == 0 && warnings == 0) {
            source.sendSuccess(() -> Component.literal("Regenerating Ore Veins config has no warnings or errors."), false);
            return;
        }

        source.sendFailure(Component.literal("Regenerating Ore Veins config has " + errors + " errors, " + warnings + " warnings. Check latest.log for full details."));
        for (String issue : firstIssues(reports, 5)) {
            source.sendFailure(Component.literal(" - " + issue));
        }
    }

    private static void sendConfigReport(ServerPlayer player, List<VeinConfig.ConfigReport> reports) {
        int errors = reports.stream().mapToInt(report -> report.errors().size()).sum();
        int warnings = reports.stream().mapToInt(report -> report.warnings().size()).sum();
        if (errors == 0 && warnings == 0) {
            return;
        }

        player.sendSystemMessage(Component.literal("Regenerating Ore Veins config has " + errors + " errors, " + warnings + " warnings. Check latest.log for full details."));
        for (String issue : firstIssues(reports, 3)) {
            player.sendSystemMessage(Component.literal(" - " + issue));
        }
    }

    private static List<String> firstIssues(List<VeinConfig.ConfigReport> reports, int limit) {
        List<String> issues = new ArrayList<>(limit);
        for (VeinConfig.ConfigReport report : reports) {
            for (String issue : report.firstIssues(limit)) {
                if (issues.size() >= limit) {
                    return issues;
                }

                issues.add(issue);
            }
        }

        return issues;
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

    private static int updateExistingCommand(CommandContext<CommandSourceStack> context, String veinId, String areaName, boolean regenerate) {
        GlobalConfig.loadFromDisk();
        VeinLocatorConfig.loadFromDisk();
        VeinConfig.LoadedConfig config = VeinConfig.loadFromDisk();
        VeinConfig.VeinDefinition requestedVein = null;
        if (!veinId.isBlank()) {
            requestedVein = config.veinsById().get(veinId);
            if (requestedVein == null) {
                context.getSource().sendFailure(Component.literal("Unknown Regenerating Ore Veins vein id: " + veinId));
                return 0;
            }
        }

        VeinConfig.AreaDefinition area = null;
        if (!areaName.isBlank()) {
            area = config.areasByName().get(areaName);
            if (area == null) {
                context.getSource().sendFailure(Component.literal("Unknown Regenerating Ore Veins area: " + areaName));
                return 0;
            }
        }

        ExistingVeinUpdateResult total = ExistingVeinUpdateResult.ZERO;
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            total = total.plus(regenerate
                    ? regenerateExistingVeinEntries(level, config, requestedVein, area)
                    : updateExistingVeinEntries(level, config, requestedVein, area));
        }

        ExistingVeinUpdateResult finalTotal = total;
        context.getSource().sendSuccess(
                () -> Component.literal(
                        (regenerate ? "Regenerated" : "Updated")
                                + " existing Regenerating Ore Veins entries: "
                                + finalTotal.updated()
                                + " updated, "
                                + finalTotal.loadedTouched()
                                + " loaded blocks touched, "
                                + finalTotal.scanned()
                                + " scanned, "
                                + finalTotal.skippedNoConfig()
                                + " skipped without matching config, "
                                + finalTotal.errors()
                                + " errors."
                ),
                true
        );
        return total.updated();
    }

    private static ExistingVeinUpdateResult updateExistingVeinEntries(
            ServerLevel level,
            VeinConfig.LoadedConfig config,
            VeinConfig.VeinDefinition requestedVein,
            VeinConfig.AreaDefinition area
    ) {
        VeinSavedData savedData = VeinSavedData.get(level);
        long[] positions = savedData.entryPositions();
        int updated = 0;
        int loadedTouched = 0;
        int skippedNoConfig = 0;
        int errors = 0;

        for (long packedPos : positions) {
            BlockPos pos = BlockPos.of(packedPos);
            if (area != null && !area.contains(level.dimension(), pos)) {
                continue;
            }

            try {
                VeinSavedData.VeinEntry entry = savedData.getEntry(pos);
                if (entry == null) {
                    continue;
                }

                VeinConfig.VeinDefinition vein = resolveVeinForExistingEntry(config, requestedVein, entry);
                if (vein == null) {
                    skippedNoConfig++;
                    continue;
                }

                BlockState oldTarget = entry.targetState();
                BlockState newTarget = vein.containsBlock(oldTarget) ? oldTarget : vein.pickBlockState(level.random);
                int effectiveInterval = VeinRuntime.applyIntervalJitter(
                        vein.regenerationIntervalSeconds(),
                        vein.regenerationIntervalJitter().rangeMin(),
                        vein.regenerationIntervalJitter().rangeMax(),
                        level.random
                );
                VeinSavedData.VeinEntry updatedEntry = new VeinSavedData.VeinEntry(
                        newTarget,
                        vein.regenerationIntervalSeconds(),
                        effectiveInterval,
                        vein.regenerationIntervalJitter().rangeMin(),
                        vein.regenerationIntervalJitter().rangeMax(),
                        entry.lastMinedEpochSecond(),
                        vein.id()
                );

                savedData.putEntry(pos, updatedEntry);
                if (level.hasChunkAt(pos)) {
                    loadedTouched += updateLoadedExistingVeinBlock(level, pos, oldTarget, updatedEntry);
                }

                updated++;
            } catch (RuntimeException exception) {
                errors++;
                RegeneratingOreVeins.LOGGER.error("Failed to update existing vein entry at {} in {}", pos, level.dimension().location(), exception);
            }
        }

        return new ExistingVeinUpdateResult(positions.length, updated, loadedTouched, skippedNoConfig, errors);
    }

    private static ExistingVeinUpdateResult regenerateExistingVeinEntries(
            ServerLevel level,
            VeinConfig.LoadedConfig config,
            VeinConfig.VeinDefinition requestedVein,
            VeinConfig.AreaDefinition area
    ) {
        VeinSavedData savedData = VeinSavedData.get(level);
        List<ExistingTrackedEntry> candidates = new ArrayList<>();
        int skippedNoConfig = 0;
        int errors = 0;

        for (long packedPos : savedData.entryPositions()) {
            BlockPos pos = BlockPos.of(packedPos);
            if (area != null && !area.contains(level.dimension(), pos)) {
                continue;
            }

            try {
                VeinSavedData.VeinEntry entry = savedData.getEntry(pos);
                if (entry == null) {
                    continue;
                }

                VeinConfig.VeinDefinition vein = resolveVeinForExistingEntry(config, requestedVein, entry);
                if (vein == null) {
                    skippedNoConfig++;
                    continue;
                }

                candidates.add(new ExistingTrackedEntry(pos, entry, vein));
            } catch (RuntimeException exception) {
                errors++;
                RegeneratingOreVeins.LOGGER.error("Failed to inspect existing vein entry at {} in {}", pos, level.dimension().location(), exception);
            }
        }

        int updated = 0;
        int loadedTouched = 0;
        for (List<ExistingTrackedEntry> group : groupExistingEntries(candidates)) {
            try {
                RegenerateGroupResult result = regenerateExistingGroup(level, savedData, group, area);
                updated += result.updated();
                loadedTouched += result.loadedTouched();
            } catch (RuntimeException exception) {
                errors++;
                RegeneratingOreVeins.LOGGER.error("Failed to regenerate existing vein group in {}", level.dimension().location(), exception);
            }
        }

        return new ExistingVeinUpdateResult(candidates.size(), updated, loadedTouched, skippedNoConfig, errors);
    }

    private static List<List<ExistingTrackedEntry>> groupExistingEntries(List<ExistingTrackedEntry> entries) {
        List<List<ExistingTrackedEntry>> groups = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            if (!visited.add(i)) {
                continue;
            }

            VeinConfig.VeinDefinition vein = entries.get(i).vein();
            int linkDistance = Math.max(6, vein.estimatedRadius() * 4);
            int linkDistanceSqr = linkDistance * linkDistance;
            List<ExistingTrackedEntry> group = new ArrayList<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(i);
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                ExistingTrackedEntry currentEntry = entries.get(current);
                group.add(currentEntry);
                for (int j = 0; j < entries.size(); j++) {
                    if (visited.contains(j)) {
                        continue;
                    }

                    ExistingTrackedEntry candidate = entries.get(j);
                    if (!candidate.vein().id().equals(vein.id())) {
                        continue;
                    }

                    if (currentEntry.pos().distSqr(candidate.pos()) <= linkDistanceSqr) {
                        visited.add(j);
                        queue.add(j);
                    }
                }
            }

            groups.add(group);
        }

        return groups;
    }

    private static RegenerateGroupResult regenerateExistingGroup(
            ServerLevel level,
            VeinSavedData savedData,
            List<ExistingTrackedEntry> group,
            VeinConfig.AreaDefinition commandArea
    ) {
        if (group.isEmpty()) {
            return RegenerateGroupResult.ZERO;
        }

        VeinConfig.VeinDefinition vein = group.getFirst().vein();
        BlockPos center = averagePosition(group);
        RandomSource random = RandomSource.create(mixSeed(level.getSeed(), new ChunkPos(center), vein.id() + ":regenerate:" + center.asLong()));
        List<BlockPos> newPositions = computeShapePositions(center, vein, random);
        Set<Long> newPositionSet = new HashSet<>();
        Map<Long, VeinSavedData.VeinEntry> newEntries = new HashMap<>();
        for (BlockPos pos : newPositions) {
            if (!level.isInWorldBounds(pos) || !vein.allowsPosition(level.dimension(), pos) || commandArea != null && !commandArea.contains(level.dimension(), pos)) {
                continue;
            }

            BlockState targetState = vein.pickBlockState(random);
            int effectiveInterval = applyIntervalJitter(vein.regenerationIntervalSeconds(), vein.regenerationIntervalJitter().rangeMin(), vein.regenerationIntervalJitter().rangeMax(), random);
            VeinSavedData.VeinEntry entry = new VeinSavedData.VeinEntry(
                    targetState,
                    vein.regenerationIntervalSeconds(),
                    effectiveInterval,
                    vein.regenerationIntervalJitter().rangeMin(),
                    vein.regenerationIntervalJitter().rangeMax(),
                    VeinSavedData.ACTIVE_LAST_MINED,
                    vein.id()
            );
            newPositionSet.add(pos.asLong());
            newEntries.put(pos.asLong(), entry);
        }

        int updated = 0;
        int loadedTouched = 0;
        for (ExistingTrackedEntry oldEntry : group) {
            if (newPositionSet.contains(oldEntry.pos().asLong())) {
                continue;
            }

            savedData.queueCleanup(oldEntry.pos(), oldEntry.entry().targetState());
            savedData.removeEntry(oldEntry.pos());
            if (level.hasChunkAt(oldEntry.pos())) {
                loadedTouched += applyPendingCleanup(level, savedData, oldEntry.pos());
            }

            updated++;
        }

        for (Map.Entry<Long, VeinSavedData.VeinEntry> mapEntry : newEntries.entrySet()) {
            BlockPos pos = BlockPos.of(mapEntry.getKey());
            savedData.putEntry(pos, mapEntry.getValue());
            savedData.queuePlacement(pos);
            if (level.hasChunkAt(pos)) {
                loadedTouched += applyPendingPlacement(level, savedData, pos);
            }

            updated++;
        }

        return new RegenerateGroupResult(updated, loadedTouched);
    }

    private static BlockPos averagePosition(List<ExistingTrackedEntry> group) {
        long x = 0L;
        long y = 0L;
        long z = 0L;
        for (ExistingTrackedEntry entry : group) {
            x += entry.pos().getX();
            y += entry.pos().getY();
            z += entry.pos().getZ();
        }

        int size = Math.max(1, group.size());
        return new BlockPos(Math.round(x / (float) size), Math.round(y / (float) size), Math.round(z / (float) size));
    }

    private static int updateLoadedExistingVeinBlock(ServerLevel level, BlockPos pos, BlockState oldTarget, VeinSavedData.VeinEntry updatedEntry) {
        BlockState current = level.getBlockState(pos);
        if (current.is(ModContent.REGENERATOR_BLOCK.get())) {
            if (level.getBlockEntity(pos) instanceof RegeneratorBlockEntity blockEntity) {
                blockEntity.configureFromEntry(updatedEntry);
                return 1;
            }

            return 0;
        }

        if (updatedEntry.lastMinedEpochSecond() == VeinSavedData.ACTIVE_LAST_MINED && current.equals(oldTarget) && !current.equals(updatedEntry.targetState())) {
            level.setBlock(pos, updatedEntry.targetState(), 3);
            return 1;
        }

        return 0;
    }

    private static void processPendingRegenerationWorldUpdates(ServerLevel level) {
        VeinSavedData savedData = VeinSavedData.get(level);
        int budget = 128;
        for (long packedPos : savedData.pendingCleanupPositions()) {
            if (budget <= 0) {
                return;
            }

            BlockPos pos = BlockPos.of(packedPos);
            if (!level.hasChunkAt(pos)) {
                continue;
            }

            budget--;
            applyPendingCleanup(level, savedData, pos);
        }

        for (long packedPos : savedData.pendingPlacementPositions()) {
            if (budget <= 0) {
                return;
            }

            BlockPos pos = BlockPos.of(packedPos);
            if (!level.hasChunkAt(pos)) {
                continue;
            }

            budget--;
            applyPendingPlacement(level, savedData, pos);
        }
    }

    private static int applyPendingCleanup(ServerLevel level, VeinSavedData savedData, BlockPos pos) {
        BlockState expectedState = savedData.pendingCleanupState(pos);
        if (expectedState == null) {
            savedData.clearCleanup(pos);
            return 0;
        }

        try {
            BlockState currentState = level.getBlockState(pos);
            if (currentState.equals(expectedState) || currentState.is(ModContent.REGENERATOR_BLOCK.get())) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                savedData.clearCleanup(pos);
                return 1;
            }

            savedData.clearCleanup(pos);
            return 0;
        } catch (RuntimeException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to apply pending regenerated vein cleanup at {}", pos, exception);
            return 0;
        }
    }

    private static int applyPendingPlacement(ServerLevel level, VeinSavedData savedData, BlockPos pos) {
        VeinSavedData.VeinEntry entry = savedData.getEntry(pos);
        if (entry == null) {
            savedData.clearPlacement(pos);
            return 0;
        }

        try {
            BlockState currentState = level.getBlockState(pos);
            if (currentState.equals(entry.targetState())) {
                savedData.clearPlacement(pos);
                return 0;
            }

            if (currentState.is(ModContent.REGENERATOR_BLOCK.get())) {
                level.setBlock(pos, entry.targetState(), 3);
                savedData.clearPlacement(pos);
                return 1;
            }

            if (currentState.isAir() || currentState.canBeReplaced() || canReplaceNatural(currentState) || isTrackedVeinTarget(currentState, entry)) {
                level.setBlock(pos, entry.targetState(), 3);
                savedData.clearPlacement(pos);
                return 1;
            }

            savedData.removeEntry(pos);
            savedData.clearPlacement(pos);
            return 0;
        } catch (RuntimeException exception) {
            RegeneratingOreVeins.LOGGER.error("Failed to apply pending regenerated vein placement at {}", pos, exception);
            return 0;
        }
    }

    private static boolean isTrackedVeinTarget(BlockState state, VeinSavedData.VeinEntry entry) {
        if (state.equals(entry.targetState())) {
            return true;
        }

        VeinConfig.VeinDefinition vein = VeinConfig.getVein(entry.veinId()).orElse(null);
        return vein != null && vein.containsBlock(state);
    }

    private static VeinConfig.VeinDefinition resolveVeinForExistingEntry(
            VeinConfig.LoadedConfig config,
            VeinConfig.VeinDefinition requestedVein,
            VeinSavedData.VeinEntry entry
    ) {
        if (requestedVein != null) {
            if (!entry.veinId().isBlank() && !requestedVein.id().equals(entry.veinId())) {
                return null;
            }

            return requestedVein.containsBlock(entry.targetState()) || requestedVein.id().equals(entry.veinId()) ? requestedVein : null;
        }

        if (!entry.veinId().isBlank()) {
            return config.veinsById().get(entry.veinId());
        }

        VeinConfig.VeinDefinition match = null;
        for (VeinConfig.VeinDefinition vein : config.veinsById().values()) {
            if (!vein.containsBlock(entry.targetState())) {
                continue;
            }

            if (match != null) {
                return null;
            }

            match = vein;
        }

        return match;
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
            processPendingRegenerationWorldUpdates(serverLevel);
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

            if (!forceReplace && !vein.allowsPosition(level.dimension(), pos)) {
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
                            VeinSavedData.ACTIVE_LAST_MINED,
                            vein.id()
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
            return Math.max(0, intervalSeconds);
        }

        RandomSource randomSource = random == null ? RandomSource.create() : random;
        long range = (long) max - (long) min + 1L;
        long jitter = min + Math.floorMod(randomSource.nextLong(), range);
        long jittered = (long) Math.max(0, intervalSeconds) + jitter;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, jittered));
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

            if (savedData.isPendingPlacement(pos)) {
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
                if (!vein.allowsPosition(level.dimension(), center)) {
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
        int radius = vein.pickRadius(random);
        List<BlockPos> positions = new ArrayList<>();

        if (vein.shape() == VeinConfig.VeinShape.BOX) {
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        positions.add(center.offset(x, y, z));
                    }
                }
            }
        } else {
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (x * x + y * y + z * z <= radius * radius) {
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

    private record ExistingVeinUpdateResult(int scanned, int updated, int loadedTouched, int skippedNoConfig, int errors) {
        private static final ExistingVeinUpdateResult ZERO = new ExistingVeinUpdateResult(0, 0, 0, 0, 0);

        private ExistingVeinUpdateResult plus(ExistingVeinUpdateResult other) {
            return new ExistingVeinUpdateResult(
                    this.scanned + other.scanned,
                    this.updated + other.updated,
                    this.loadedTouched + other.loadedTouched,
                    this.skippedNoConfig + other.skippedNoConfig,
                    this.errors + other.errors
            );
        }
    }

    private record ExistingTrackedEntry(BlockPos pos, VeinSavedData.VeinEntry entry, VeinConfig.VeinDefinition vein) {
    }

    private record RegenerateGroupResult(int updated, int loadedTouched) {
        private static final RegenerateGroupResult ZERO = new RegenerateGroupResult(0, 0);
    }
}
