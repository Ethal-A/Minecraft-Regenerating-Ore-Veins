package net.stargazer.regenerating_ore_veins;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

public final class VeinStructurePiece extends StructurePiece {
    private final BlockPos origin;
    private final String configId;

    public VeinStructurePiece(BlockPos origin, String configId, int radius) {
        super(
                ModContent.VEIN_STRUCTURE_PIECE.get(),
                0,
                new BoundingBox(
                        origin.getX() - radius,
                        origin.getY() - radius,
                        origin.getZ() - radius,
                        origin.getX() + radius,
                        origin.getY() + radius,
                        origin.getZ() + radius
                )
        );
        this.origin = origin.immutable();
        this.configId = configId;
    }

    public VeinStructurePiece(CompoundTag tag) {
        super(ModContent.VEIN_STRUCTURE_PIECE.get(), tag);
        this.origin = new BlockPos(tag.getInt("OriginX"), tag.getInt("OriginY"), tag.getInt("OriginZ"));
        this.configId = tag.getString("ConfigId");
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putInt("OriginX", this.origin.getX());
        tag.putInt("OriginY", this.origin.getY());
        tag.putInt("OriginZ", this.origin.getZ());
        tag.putString("ConfigId", this.configId);
    }

    @Override
    public void postProcess(
            WorldGenLevel level,
            StructureManager structureManager,
            ChunkGenerator generator,
            RandomSource random,
            BoundingBox box,
            ChunkPos chunkPos,
            BlockPos pos
    ) {
        if (!(level.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockPos placementOrigin = pos == null ? this.origin : pos;
        VeinConfig.getVein(this.configId).ifPresent(vein -> VeinRuntime.placeManagedVein(serverLevel, placementOrigin, vein, random, true));
    }
}
