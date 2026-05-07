package net.stargazer.regenerating_ore_veins;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModContent {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RegeneratingOreVeins.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RegeneratingOreVeins.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, RegeneratingOreVeins.MOD_ID);
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, RegeneratingOreVeins.MOD_ID);
    public static final DeferredRegister<StructurePieceType> STRUCTURE_PIECES = DeferredRegister.create(Registries.STRUCTURE_PIECE, RegeneratingOreVeins.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RegeneratingOreVeins.MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, RegeneratingOreVeins.MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, RegeneratingOreVeins.MOD_ID);

    public static final DeferredBlock<RegeneratorBlock> REGENERATOR_BLOCK = BLOCKS.register("regenerator_block", RegeneratorBlock::new);
    public static final DeferredItem<BlockItem> REGENERATOR_BLOCK_ITEM = ITEMS.registerSimpleBlockItem(REGENERATOR_BLOCK, new Item.Properties());
    public static final DeferredItem<VeinLocatorItem> VEIN_LOCATOR = ITEMS.register("vein_locator", () -> new VeinLocatorItem(new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RegeneratorBlockEntity>> REGENERATOR_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register(
                    "regenerator_block_entity",
                    () -> BlockEntityType.Builder.of(RegeneratorBlockEntity::new, REGENERATOR_BLOCK.get()).build(null)
            );
    public static final DeferredHolder<StructureType<?>, StructureType<VeinStructure>> VEIN_STRUCTURE =
            STRUCTURE_TYPES.register("vein", () -> structureCodec(VeinStructure.CODEC));
    public static final DeferredHolder<StructurePieceType, StructurePieceType> VEIN_STRUCTURE_PIECE =
            STRUCTURE_PIECES.register("vein_piece", () -> (StructurePieceType.ContextlessType) VeinStructurePiece::new);
    public static final DeferredHolder<EntityType<?>, EntityType<VeinLocatorSignalEntity>> VEIN_LOCATOR_SIGNAL =
            ENTITY_TYPES.register(
                    "vein_locator_signal",
                    () -> EntityType.Builder.<VeinLocatorSignalEntity>of(VeinLocatorSignalEntity::new, MobCategory.MISC)
                            .sized(0.25F, 0.25F)
                            .clientTrackingRange(4)
                            .updateInterval(4)
                            .build("vein_locator_signal")
            );
    public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<VeinLocatorTuningRecipe>> VEIN_LOCATOR_TUNING_RECIPE =
            RECIPE_SERIALIZERS.register("vein_locator_tuning", () -> new SimpleCraftingRecipeSerializer<>(VeinLocatorTuningRecipe::new));
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB =
            CREATIVE_MODE_TABS.register(
                    "regenerating_ore_veins",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.regenerating_ore_veins"))
                            .icon(() -> new ItemStack(REGENERATOR_BLOCK_ITEM.get()))
                            .displayItems((parameters, output) -> {
                                output.accept(REGENERATOR_BLOCK_ITEM.get());
                                output.accept(VEIN_LOCATOR.get());
                            })
                            .build()
            );

    private ModContent() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        STRUCTURE_TYPES.register(modEventBus);
        STRUCTURE_PIECES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        RECIPE_SERIALIZERS.register(modEventBus);
    }

    private static <S extends net.minecraft.world.level.levelgen.structure.Structure> StructureType<S> structureCodec(MapCodec<S> codec) {
        return () -> codec;
    }
}
