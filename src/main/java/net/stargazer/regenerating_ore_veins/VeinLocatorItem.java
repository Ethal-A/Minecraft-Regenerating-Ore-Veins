package net.stargazer.regenerating_ore_veins;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gameevent.GameEvent;

public final class VeinLocatorItem extends Item {
    private static final String TARGET_BLOCK_KEY = "TargetBlock";

    public VeinLocatorItem(Properties properties) {
        super(properties.stacksTo(64));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        VeinLocatorConfig.Values config = VeinLocatorConfig.get();
        ItemStack stack = prepareHeldStackForUse(player, hand, config);
        if (!config.allowUse()) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("message.regenerating_ore_veins.locator_disabled"), false);
            }

            return InteractionResultHolder.fail(stack);
        }

        Optional<Block> targetBlock = getTargetBlock(stack);
        if (targetBlock.isEmpty()) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("message.regenerating_ore_veins.locator_not_attuned"), false);
            }

            return InteractionResultHolder.fail(stack);
        }

        player.startUsingItem(hand);
        if (level instanceof ServerLevel serverLevel) {
            BlockPos targetPos = VeinSavedData.get(serverLevel).findNearest(player.blockPosition(), targetBlock.get());
            if (targetPos == null) {
                player.displayClientMessage(Component.translatable("message.regenerating_ore_veins.locator_no_match", targetBlock.get().getName()), false);
                return InteractionResultHolder.consume(stack);
            }

            VeinLocatorSignalEntity signal = new VeinLocatorSignalEntity(serverLevel, player.getX(), player.getY(0.5D), player.getZ());
            signal.setItem(stack);
            signal.signalTo(targetPos);
            signal.setBreakEffect(consumeUseCost(stack, player, hand, config, serverLevel));
            level.gameEvent(GameEvent.PROJECTILE_SHOOT, signal.position(), GameEvent.Context.of(player));
            level.addFreshEntity(signal);

            float pitch = Mth.lerp(level.random.nextFloat(), 0.33F, 0.5F);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDER_EYE_LAUNCH, SoundSource.NEUTRAL, 1.0F, pitch);
            player.awardStat(Stats.ITEM_USED.get(this));
            player.swing(hand, true);
            player.displayClientMessage(Component.translatable("message.regenerating_ore_veins.locator_found", targetBlock.get().getName()), true);

            return InteractionResultHolder.success(stack);
        }

        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        VeinLocatorConfig.Values config = VeinLocatorConfig.get();
        if (config.useDurability() && entity instanceof Player player && !level.isClientSide && stack.getCount() > 1) {
            splitDurabilityStack(player, stack, config);
            return;
        }

        normalizeLocatorStack(stack, config);
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        VeinLocatorConfig.Values config = VeinLocatorConfig.get();
        return config.useDurability() || stack.has(DataComponents.MAX_DAMAGE) ? 1 : config.stackSize();
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return VeinLocatorConfig.get().useDurability() && stack.isDamaged();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        Optional<Block> targetBlock = getTargetBlock(stack);
        tooltipComponents.add(targetBlock
                .<Component>map(block -> Component.translatable("tooltip.regenerating_ore_veins.vein_locator_attuned", block.getName()))
                .orElseGet(() -> Component.translatable("tooltip.regenerating_ore_veins.vein_locator_unattuned")));
    }

    public static ItemStack createAttuned(ItemStack locator, Block block) {
        ItemStack result = locator.copyWithCount(1);
        CompoundTag tag = result.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putString(TARGET_BLOCK_KEY, BuiltInRegistries.BLOCK.getKey(block).toString());
        CustomData.set(DataComponents.CUSTOM_DATA, result, tag);
        result.setDamageValue(0);
        normalizeLocatorStack(result, VeinLocatorConfig.get());
        return result;
    }

    private static Optional<Block> getTargetBlock(ItemStack stack) {
        CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (!customData.contains(TARGET_BLOCK_KEY)) {
            return Optional.empty();
        }

        ResourceLocation location;
        try {
            location = ResourceLocation.parse(customData.copyTag().getString(TARGET_BLOCK_KEY));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }

        return BuiltInRegistries.BLOCK.containsKey(location) ? Optional.of(BuiltInRegistries.BLOCK.get(location)) : Optional.empty();
    }

    private static ItemStack prepareHeldStackForUse(Player player, InteractionHand hand, VeinLocatorConfig.Values config) {
        ItemStack stack = player.getItemInHand(hand);
        if (config.useDurability() && stack.getCount() > 1 && !player.getAbilities().instabuild) {
            ItemStack single = stack.copyWithCount(1);
            ItemStack remainder = stack.copyWithCount(stack.getCount() - 1);
            normalizeLocatorStack(single, config);
            player.setItemInHand(hand, single);
            addOrDropSplitStack(player, remainder, config);
            return single;
        }

        normalizeLocatorStack(stack, config);
        return stack;
    }

    private static boolean consumeUseCost(ItemStack stack, Player player, InteractionHand hand, VeinLocatorConfig.Values config, ServerLevel level) {
        if (player.getAbilities().instabuild) {
            return config.useDurability();
        }

        if (config.useDurability()) {
            normalizeLocatorStack(stack, config);
            EquipmentSlot slot = hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
            stack.hurtAndBreak(1, player, slot);
            return true;
        }

        if (level.random.nextDouble() < config.chanceToBreak()) {
            stack.shrink(1);
            return true;
        }

        return false;
    }

    private static void splitDurabilityStack(Player player, ItemStack stack, VeinLocatorConfig.Values config) {
        int extra = stack.getCount() - 1;
        stack.setCount(1);
        normalizeLocatorStack(stack, config);
        for (int i = 0; i < extra; i++) {
            ItemStack single = stack.copyWithCount(1);
            normalizeLocatorStack(single, config);
            if (!player.addItem(single)) {
                player.drop(single, false);
            }
        }
    }

    private static void addOrDropSplitStack(Player player, ItemStack stack, VeinLocatorConfig.Values config) {
        int count = stack.getCount();
        for (int i = 0; i < count; i++) {
            ItemStack single = stack.copyWithCount(1);
            normalizeLocatorStack(single, config);
            if (!player.addItem(single)) {
                player.drop(single, false);
            }
        }
    }

    private static void normalizeLocatorStack(ItemStack stack, VeinLocatorConfig.Values config) {
        if (stack.isEmpty() || !stack.is(ModContent.VEIN_LOCATOR.get())) {
            return;
        }

        if (config.useDurability()) {
            int durability = Math.max(1, config.durability());
            stack.set(DataComponents.MAX_STACK_SIZE, 1);
            stack.set(DataComponents.MAX_DAMAGE, durability);
            stack.set(DataComponents.DAMAGE, Math.max(0, Math.min(stack.getOrDefault(DataComponents.DAMAGE, 0), durability - 1)));
        } else {
            stack.remove(DataComponents.MAX_DAMAGE);
            stack.remove(DataComponents.DAMAGE);
            stack.set(DataComponents.MAX_STACK_SIZE, config.stackSize());
        }
    }
}
