package net.stargazer.regenerating_ore_veins;

import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class RegeneratorBlockEntity extends BlockEntity {
    private BlockState targetState = Blocks.AIR.defaultBlockState();
    private int intervalSeconds = GlobalConfig.get().defaultRegenerationSeconds();
    private int effectiveIntervalSeconds = this.intervalSeconds;
    private int jitterRangeMinSeconds;
    private int jitterRangeMaxSeconds;
    private long lastMinedEpochSecond = epochSeconds();
    private int lastSyncedHash = Integer.MIN_VALUE;

    public RegeneratorBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModContent.REGENERATOR_BLOCK_ENTITY.get(), pos, blockState);
    }

    public void serverTick() {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }

        VeinSavedData savedData = VeinSavedData.get(serverLevel);
        VeinSavedData.VeinEntry entry = savedData.getEntry(this.worldPosition);
        if (entry != null) {
            this.targetState = entry.targetState();
            this.intervalSeconds = entry.intervalSeconds();
            this.effectiveIntervalSeconds = entry.effectiveIntervalSeconds();
            this.jitterRangeMinSeconds = entry.jitterRangeMinSeconds();
            this.jitterRangeMaxSeconds = entry.jitterRangeMaxSeconds();
            this.lastMinedEpochSecond = entry.lastMinedEpochSecond();
        } else if (this.hasValidTarget()) {
            this.pushToSavedData(savedData, epochSeconds());
        }

        int syncHash = this.computeSyncHash();
        if (syncHash != this.lastSyncedHash && this.hasValidTarget()) {
            this.pushToSavedData(savedData, this.lastMinedEpochSecond);
            this.lastSyncedHash = syncHash;
        }

        if (this.hasValidTarget() && this.lastMinedEpochSecond != VeinSavedData.ACTIVE_LAST_MINED && this.getRemainingSeconds() <= 0L) {
            if (!this.getBlockState().is(ModContent.REGENERATOR_BLOCK.get())) {
                return;
            }

            savedData.markActive(this.worldPosition);
            serverLevel.setBlock(this.worldPosition, this.targetState, 3);
            VeinRuntime.spawnRegenerationSmoke(serverLevel, this.worldPosition);
        }
    }

    public void setConfiguration(BlockState targetState, int intervalSeconds, int jitterRangeMinSeconds, int jitterRangeMaxSeconds) {
        this.targetState = targetState;
        this.intervalSeconds = Math.max(1, intervalSeconds);
        this.jitterRangeMinSeconds = Math.min(jitterRangeMinSeconds, jitterRangeMaxSeconds);
        this.jitterRangeMaxSeconds = Math.max(jitterRangeMinSeconds, jitterRangeMaxSeconds);
        this.effectiveIntervalSeconds = VeinRuntime.applyIntervalJitter(this.intervalSeconds, this.jitterRangeMinSeconds, this.jitterRangeMaxSeconds, this.level == null ? null : this.level.random);
        this.lastMinedEpochSecond = epochSeconds();
        this.setChangedAndUpdate();
        this.syncToSavedData();
    }

    public void configureFromEntry(VeinSavedData.VeinEntry entry) {
        this.targetState = entry.targetState();
        this.intervalSeconds = entry.intervalSeconds();
        this.effectiveIntervalSeconds = entry.effectiveIntervalSeconds();
        this.jitterRangeMinSeconds = entry.jitterRangeMinSeconds();
        this.jitterRangeMaxSeconds = entry.jitterRangeMaxSeconds();
        this.lastMinedEpochSecond = entry.lastMinedEpochSecond();
        this.setChangedAndUpdate();
    }

    public void syncToSavedData() {
        if (this.level instanceof ServerLevel serverLevel && this.hasValidTarget()) {
            this.pushToSavedData(VeinSavedData.get(serverLevel), this.lastMinedEpochSecond);
        }
    }

    public String getTargetName() {
        return this.targetState.getBlock().getName().getString();
    }

    public BlockState getTargetState() {
        return this.targetState;
    }

    public ResourceLocation getTargetId() {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(this.targetState.getBlock());
    }

    public int getIntervalSeconds() {
        return this.intervalSeconds;
    }

    public int getEffectiveIntervalSeconds() {
        return this.effectiveIntervalSeconds;
    }

    public int getJitterRangeMinSeconds() {
        return this.jitterRangeMinSeconds;
    }

    public int getJitterRangeMaxSeconds() {
        return this.jitterRangeMaxSeconds;
    }

    public boolean hasValidTarget() {
        return !this.targetState.isAir();
    }

    public long getRemainingSeconds() {
        if (!this.hasValidTarget()) {
            return 0L;
        }

        long elapsed = Math.max(0L, epochSeconds() - this.lastMinedEpochSecond);
        return Math.max(0L, (long) this.effectiveIntervalSeconds - elapsed);
    }

    public void sendInspectionMessage(Player player) {
        if (!this.hasValidTarget()) {
            player.displayClientMessage(Component.translatable("message.regenerating_ore_veins.no_target"), false);
            return;
        }

        player.displayClientMessage(
                Component.translatable(
                        "message.regenerating_ore_veins.inspect",
                        this.getTargetName(),
                        VeinRuntime.formatDuration(this.getRemainingSeconds()),
                        this.intervalSeconds,
                        this.jitterRangeMinSeconds,
                        this.jitterRangeMaxSeconds
                ),
                false
        );
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("TargetState")) {
            this.targetState = NbtUtils.readBlockState(registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), tag.getCompound("TargetState"));
        }

        this.intervalSeconds = tag.contains("IntervalSeconds") ? Math.max(1, tag.getInt("IntervalSeconds")) : GlobalConfig.get().defaultRegenerationSeconds();
        this.effectiveIntervalSeconds = tag.contains("EffectiveIntervalSeconds") ? Math.max(1, tag.getInt("EffectiveIntervalSeconds")) : this.intervalSeconds;
        this.jitterRangeMinSeconds = tag.getInt("JitterRangeMinSeconds");
        this.jitterRangeMaxSeconds = tag.getInt("JitterRangeMaxSeconds");
        if (this.jitterRangeMinSeconds > this.jitterRangeMaxSeconds) {
            int oldMin = this.jitterRangeMinSeconds;
            this.jitterRangeMinSeconds = this.jitterRangeMaxSeconds;
            this.jitterRangeMaxSeconds = oldMin;
        }

        this.lastMinedEpochSecond = tag.contains("LastMinedEpochSecond") ? tag.getLong("LastMinedEpochSecond") : epochSeconds();
        this.lastSyncedHash = Integer.MIN_VALUE;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("TargetState", NbtUtils.writeBlockState(this.targetState));
        tag.putInt("IntervalSeconds", this.intervalSeconds);
        tag.putInt("EffectiveIntervalSeconds", this.effectiveIntervalSeconds);
        tag.putInt("JitterRangeMinSeconds", this.jitterRangeMinSeconds);
        tag.putInt("JitterRangeMaxSeconds", this.jitterRangeMaxSeconds);
        tag.putLong("LastMinedEpochSecond", this.lastMinedEpochSecond);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void pushToSavedData(VeinSavedData savedData, long lastMined) {
        savedData.putEntry(
                this.worldPosition,
                new VeinSavedData.VeinEntry(
                        this.targetState,
                        Math.max(1, this.intervalSeconds),
                        Math.max(1, this.effectiveIntervalSeconds),
                        this.jitterRangeMinSeconds,
                        this.jitterRangeMaxSeconds,
                        lastMined
                )
        );
        this.lastMinedEpochSecond = lastMined;
        this.setChangedAndUpdate();
    }

    private int computeSyncHash() {
        int result = this.targetState.hashCode();
        result = 31 * result + this.intervalSeconds;
        result = 31 * result + this.effectiveIntervalSeconds;
        result = 31 * result + this.jitterRangeMinSeconds;
        result = 31 * result + this.jitterRangeMaxSeconds;
        result = 31 * result + Long.hashCode(this.lastMinedEpochSecond);
        return result;
    }

    private void setChangedAndUpdate() {
        this.setChanged();
        if (this.level != null) {
            BlockState state = this.getBlockState();
            this.level.sendBlockUpdated(this.worldPosition, state, state, 3);
        }
    }

    private static long epochSeconds() {
        return TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
    }
}
