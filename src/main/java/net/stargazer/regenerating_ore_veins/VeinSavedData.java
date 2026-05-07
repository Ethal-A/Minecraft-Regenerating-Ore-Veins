package net.stargazer.regenerating_ore_veins;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

public final class VeinSavedData extends SavedData {
    private static final String DATA_NAME = RegeneratingOreVeins.MOD_ID + "_veins";
    public static final long ACTIVE_LAST_MINED = -1L;

    private final Long2ObjectOpenHashMap<VeinEntry> entries = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<BlockState> pendingCleanup = new Long2ObjectOpenHashMap<>();
    private final LongSet pendingPlacement = new LongOpenHashSet();
    private final LongSet generatedChunks = new LongOpenHashSet();

    public static VeinSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(VeinSavedData::new, VeinSavedData::load), DATA_NAME);
    }

    public static VeinSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        VeinSavedData data = new VeinSavedData();
        ListTag entriesTag = tag.getList("Entries", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < entriesTag.size(); i++) {
            CompoundTag entryTag = entriesTag.getCompound(i);
            long pos = entryTag.getLong("Pos");
            BlockState state = NbtUtils.readBlockState(registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), entryTag.getCompound("TargetState"));
            int interval = Math.max(0, entryTag.getInt("IntervalSeconds"));
            int effectiveInterval = entryTag.contains("EffectiveIntervalSeconds") ? Math.max(0, entryTag.getInt("EffectiveIntervalSeconds")) : interval;
            int jitterMin = entryTag.getInt("JitterRangeMinSeconds");
            int jitterMax = entryTag.getInt("JitterRangeMaxSeconds");
            if (jitterMin > jitterMax) {
                int oldMin = jitterMin;
                jitterMin = jitterMax;
                jitterMax = oldMin;
            }

            long lastMined = entryTag.getLong("LastMinedEpochSecond");
            String veinId = entryTag.getString("VeinId");
            data.entries.put(pos, new VeinEntry(state, interval, effectiveInterval, jitterMin, jitterMax, lastMined, veinId));
        }

        for (long chunk : tag.getLongArray("GeneratedChunks")) {
            data.generatedChunks.add(chunk);
        }

        ListTag cleanupTag = tag.getList("PendingCleanup", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < cleanupTag.size(); i++) {
            CompoundTag entryTag = cleanupTag.getCompound(i);
            BlockState state = NbtUtils.readBlockState(registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), entryTag.getCompound("TargetState"));
            data.pendingCleanup.put(entryTag.getLong("Pos"), state);
        }

        for (long pos : tag.getLongArray("PendingPlacement")) {
            data.pendingPlacement.add(pos);
        }

        return data;
    }

    public VeinEntry getEntry(BlockPos pos) {
        return this.entries.get(pos.asLong());
    }

    public long[] entryPositions() {
        return this.entries.keySet().toLongArray();
    }

    public BlockPos findNearest(BlockPos origin, Block targetBlock) {
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Map.Entry<Long, VeinEntry> mapEntry : this.entries.long2ObjectEntrySet()) {
            VeinEntry entry = mapEntry.getValue();
            if (!entry.targetState().is(targetBlock)) {
                continue;
            }

            BlockPos pos = BlockPos.of(mapEntry.getKey());
            double distance = pos.distSqr(origin);
            if (distance < nearestDistance) {
                nearest = pos;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    public void putEntry(BlockPos pos, VeinEntry entry) {
        this.entries.put(pos.asLong(), entry);
        this.setDirty();
    }

    public void removeEntry(BlockPos pos) {
        if (this.entries.remove(pos.asLong()) != null) {
            this.setDirty();
        }
    }

    public void queueCleanup(BlockPos pos, BlockState expectedState) {
        this.pendingCleanup.put(pos.asLong(), expectedState);
        this.setDirty();
    }

    public void clearCleanup(BlockPos pos) {
        if (this.pendingCleanup.remove(pos.asLong()) != null) {
            this.setDirty();
        }
    }

    public long[] pendingCleanupPositions() {
        return this.pendingCleanup.keySet().toLongArray();
    }

    public BlockState pendingCleanupState(BlockPos pos) {
        return this.pendingCleanup.get(pos.asLong());
    }

    public void queuePlacement(BlockPos pos) {
        if (this.pendingPlacement.add(pos.asLong())) {
            this.setDirty();
        }
    }

    public void clearPlacement(BlockPos pos) {
        if (this.pendingPlacement.remove(pos.asLong())) {
            this.setDirty();
        }
    }

    public boolean isPendingPlacement(BlockPos pos) {
        return this.pendingPlacement.contains(pos.asLong());
    }

    public long[] pendingPlacementPositions() {
        return this.pendingPlacement.toLongArray();
    }

    public void markMined(BlockPos pos, long lastMinedEpochSecond) {
        VeinEntry entry = this.getEntry(pos);
        if (entry != null) {
            int effectiveInterval = VeinRuntime.applyIntervalJitter(entry.intervalSeconds(), entry.jitterRangeMinSeconds(), entry.jitterRangeMaxSeconds(), null);
            this.entries.put(
                    pos.asLong(),
                    new VeinEntry(
                            entry.targetState(),
                            entry.intervalSeconds(),
                            effectiveInterval,
                            entry.jitterRangeMinSeconds(),
                            entry.jitterRangeMaxSeconds(),
                            lastMinedEpochSecond,
                            entry.veinId()
                    )
            );
            this.setDirty();
        }
    }

    public void markActive(BlockPos pos) {
        VeinEntry entry = this.getEntry(pos);
        if (entry != null && entry.lastMinedEpochSecond() != ACTIVE_LAST_MINED) {
            this.entries.put(
                    pos.asLong(),
                    new VeinEntry(
                            entry.targetState(),
                            entry.intervalSeconds(),
                            entry.effectiveIntervalSeconds(),
                            entry.jitterRangeMinSeconds(),
                            entry.jitterRangeMaxSeconds(),
                            ACTIVE_LAST_MINED,
                            entry.veinId()
                    )
            );
            this.setDirty();
        }
    }

    public boolean isChunkGenerated(long chunkLong) {
        return this.generatedChunks.contains(chunkLong);
    }

    public void markChunkGenerated(long chunkLong) {
        if (this.generatedChunks.add(chunkLong)) {
            this.setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entriesTag = new ListTag();
        for (Map.Entry<Long, VeinEntry> mapEntry : this.entries.long2ObjectEntrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("Pos", mapEntry.getKey());
            entryTag.put("TargetState", NbtUtils.writeBlockState(mapEntry.getValue().targetState()));
            entryTag.putInt("IntervalSeconds", mapEntry.getValue().intervalSeconds());
            entryTag.putInt("EffectiveIntervalSeconds", mapEntry.getValue().effectiveIntervalSeconds());
            entryTag.putInt("JitterRangeMinSeconds", mapEntry.getValue().jitterRangeMinSeconds());
            entryTag.putInt("JitterRangeMaxSeconds", mapEntry.getValue().jitterRangeMaxSeconds());
            entryTag.putLong("LastMinedEpochSecond", mapEntry.getValue().lastMinedEpochSecond());
            entryTag.putString("VeinId", mapEntry.getValue().veinId());
            entriesTag.add(entryTag);
        }

        tag.put("Entries", entriesTag);
        tag.putLongArray("GeneratedChunks", this.generatedChunks.toLongArray());
        ListTag cleanupTag = new ListTag();
        for (Map.Entry<Long, BlockState> mapEntry : this.pendingCleanup.long2ObjectEntrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("Pos", mapEntry.getKey());
            entryTag.put("TargetState", NbtUtils.writeBlockState(mapEntry.getValue()));
            cleanupTag.add(entryTag);
        }

        tag.put("PendingCleanup", cleanupTag);
        tag.putLongArray("PendingPlacement", this.pendingPlacement.toLongArray());
        return tag;
    }

    public record VeinEntry(
            BlockState targetState,
            int intervalSeconds,
            int effectiveIntervalSeconds,
            int jitterRangeMinSeconds,
            int jitterRangeMaxSeconds,
            long lastMinedEpochSecond,
            String veinId
    ) {
    }
}
