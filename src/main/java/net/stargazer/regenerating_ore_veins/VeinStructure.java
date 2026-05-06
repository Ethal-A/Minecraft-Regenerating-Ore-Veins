package net.stargazer.regenerating_ore_veins;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

public final class VeinStructure extends Structure {
    public static final MapCodec<VeinStructure> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Structure.settingsCodec(instance),
            Codec.STRING.fieldOf("config_id").forGetter(VeinStructure::configId)
    ).apply(instance, VeinStructure::new));

    private final String configId;

    public VeinStructure(StructureSettings settings, String configId) {
        super(settings);
        this.configId = configId;
    }

    public String configId() {
        return this.configId;
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        Optional<VeinConfig.VeinDefinition> optional = VeinConfig.getVein(this.configId);
        if (optional.isEmpty()) {
            return Optional.empty();
        }

        VeinConfig.VeinDefinition vein = optional.get();
        int minY = Math.min(vein.minY(), vein.maxY());
        int maxY = Math.max(vein.minY(), vein.maxY());
        int y = Mth.nextInt(context.random(), minY, maxY);
        BlockPos origin = new BlockPos(context.chunkPos().getMiddleBlockX(), y, context.chunkPos().getMiddleBlockZ());
        return Optional.of(new GenerationStub(origin, builder -> builder.addPiece(new VeinStructurePiece(origin, vein.id(), vein.estimatedRadius()))));
    }

    @Override
    public StructureType<?> type() {
        return ModContent.VEIN_STRUCTURE.get();
    }
}
