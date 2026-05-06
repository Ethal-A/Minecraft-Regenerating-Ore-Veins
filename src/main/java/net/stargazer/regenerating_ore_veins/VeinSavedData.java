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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

public final class VeinSavedData extends SavedData {
    private static final String DATA_NAME = RegeneratingOreVeins.MOD_ID + "_veins";
    public static final long ACTIVE_LAST_MINED = -1L;

    private final Long2ObjectOpenHashMap<VeinEntry> entries = new Long2ObjectOpenHashMap<>();
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
            int interval = Math.max(1, entryTag.getInt("IntervalSeconds"));
            int effectiveInterval = entryTag.contains("EffectiveIntervalSeconds") ? Math.max(1, entryTag.getInt("EffectiveIntervalSeconds")) : interval;
            int jitterMin = entryTag.getInt("JitterRangeMinSeconds");
            int jitterMax = entryTag.getInt("JitterRangeMaxSeconds");
            if (jitterMin > jitterMax) {
                int oldMin = jitterMin;
                jitterMin = jitterMax;
                jitterMax = oldMin;
            }

            long lastMined = entryTag.getLong("LastMinedEpochSecond");
            data.entries.put(pos, new VeinEntry(state, interval, effectiveInterval, jitterMin, jitterMax, lastMined));
        }

        for (long chunk : tag.getLongArray("GeneratedChunks")) {
            data.generatedChunks.add(chunk);
        }

        return data;
    }

    public VeinEntry getEntry(BlockPos pos) {
        return this.entries.get(pos.asLong());
    }

    public long[] entryPositions() {
        return this.entries.keySet().toLongArray();
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
                            lastMinedEpochSecond
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
                            ACTIVE_LAST_MINED
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
            entriesTag.add(entryTag);
        }

        tag.put("Entries", entriesTag);
        tag.putLongArray("GeneratedChunks", this.generatedChunks.toLongArray());
        return tag;
    }

    public record VeinEntry(
            BlockState targetState,
            int intervalSeconds,
            int effectiveIntervalSeconds,
            int jitterRangeMinSeconds,
            int jitterRangeMaxSeconds,
            long lastMinedEpochSecond
    ) {
    }
}
