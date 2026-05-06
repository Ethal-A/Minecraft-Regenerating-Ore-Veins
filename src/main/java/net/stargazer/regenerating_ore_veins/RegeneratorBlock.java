package net.stargazer.regenerating_ore_veins;

import com.mojang.serialization.MapCodec;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.event.EventHooks;

public final class RegeneratorBlock extends BaseEntityBlock implements EntityBlock {
    public static final MapCodec<RegeneratorBlock> CODEC = simpleCodec(RegeneratorBlock::new);
    private static final VoxelShape OUTLINE = Block.box(1.0D, 1.0D, 1.0D, 15.0D, 15.0D, 15.0D);
    private static final float BASE_HARDNESS = 5.0F;
    private static final Map<Class<? extends Item>, Boolean> CUSTOM_ITEM_INTERACTION_CACHE = new ConcurrentHashMap<>();

    public RegeneratorBlock() {
        this(BlockBehaviour.Properties.of()
                .strength(BASE_HARDNESS, 3_600_000.0F)
                .sound(SoundType.GLASS)
                .noCollission()
                .noOcclusion()
                .pushReaction(PushReaction.BLOCK)
                .isValidSpawn((state, level, pos, type) -> false)
                .isSuffocating((state, level, pos) -> false)
                .isViewBlocking((state, level, pos) -> false));
    }

    private RegeneratorBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return OUTLINE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected boolean isPathfindable(BlockState state, net.minecraft.world.level.pathfinder.PathComputationType pathComputationType) {
        return true;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (level.isClientSide || newState.is(this) || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        VeinSavedData.VeinEntry entry = VeinSavedData.get(serverLevel).getEntry(pos);
        if (entry != null && entry.lastMinedEpochSecond() != VeinSavedData.ACTIVE_LAST_MINED) {
            VeinRuntime.queueRegeneratorReplacement(serverLevel, pos);
        }
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (player.isCreative()) {
            return 1.0F;
        }

        GlobalConfig.Values config = GlobalConfig.get();
        if (!config.allowBreaking() || !player.isShiftKeyDown()) {
            return 0.0F;
        }

        double configuredHardness = config.breakHardness();
        if (configuredHardness <= 0.0D) {
            return 1.0F;
        }

        int divisor = EventHooks.doPlayerHarvestCheck(player, state, level, pos) ? 30 : 100;
        return player.getDigSpeed(state, pos) / (float) configuredHardness / (float) divisor;
    }

    @Override
    public boolean canEntityDestroy(BlockState state, BlockGetter level, BlockPos pos, Entity entity) {
        return false;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        if (level.getBlockEntity(pos) instanceof RegeneratorBlockEntity blockEntity) {
            if (player.isCreative()) {
                if (level.isClientSide) {
                    RegeneratorClient.openConfigScreen(pos, blockEntity);
                }
            } else if (!level.isClientSide) {
                blockEntity.sendInspectionMessage(player);
            }

            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        return InteractionResult.PASS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!player.isShiftKeyDown()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (!shouldInspectWithHeldStack(stack, level, pos, player)) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }

        if (!(level.getBlockEntity(pos) instanceof RegeneratorBlockEntity blockEntity)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (player.isCreative()) {
            if (level.isClientSide) {
                RegeneratorClient.openConfigScreen(pos, blockEntity);
            }
        } else if (!level.isClientSide) {
            blockEntity.sendInspectionMessage(player);
        }

        return ItemInteractionResult.SUCCESS;
    }

    public static boolean shouldInspectWithHeldStack(ItemStack stack, LevelReader level, BlockPos pos, Player player) {
        if (stack.isEmpty()) {
            return true;
        }

        Item item = stack.getItem();
        if (item instanceof BlockItem || item instanceof ShieldItem) {
            return false;
        }

        if (stack.getUseAnimation() != UseAnim.NONE || stack.getUseDuration(player) > 0) {
            return false;
        }

        return !hasCustomItemInteraction(item);
    }

    private static boolean hasCustomItemInteraction(Item item) {
        return CUSTOM_ITEM_INTERACTION_CACHE.computeIfAbsent(item.getClass(), itemClass ->
                overrides(itemClass, Item.class, "use", Level.class, Player.class, InteractionHand.class)
                        || overrides(itemClass, Item.class, "useOn", UseOnContext.class)
                        || overrides(itemClass, net.neoforged.neoforge.common.extensions.IItemExtension.class, "onItemUseFirst", ItemStack.class, UseOnContext.class)
                        || overrides(itemClass, net.neoforged.neoforge.common.extensions.IItemExtension.class, "doesSneakBypassUse", ItemStack.class, LevelReader.class, BlockPos.class, Player.class)
        );
    }

    private static boolean overrides(Class<? extends Item> itemClass, Class<?> baseClass, String methodName, Class<?>... parameterTypes) {
        try {
            Method method = itemClass.getMethod(methodName, parameterTypes);
            return method.getDeclaringClass() != baseClass;
        } catch (NoSuchMethodException exception) {
            return false;
        }
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RegeneratorBlockEntity(pos, state);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        ItemStack stack = new ItemStack(this);
        if (level.getBlockEntity(pos) instanceof RegeneratorBlockEntity blockEntity) {
            blockEntity.saveToItem(stack, level.registryAccess());
        }

        return stack;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof RegeneratorBlockEntity blockEntity && blockEntity.hasValidTarget()) {
            blockEntity.syncToSavedData();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return level instanceof ServerLevel
                ? createTickerHelper(blockEntityType, ModContent.REGENERATOR_BLOCK_ENTITY.get(), (lvl, pos, blockState, be) -> be.serverTick())
                : null;
    }
}
