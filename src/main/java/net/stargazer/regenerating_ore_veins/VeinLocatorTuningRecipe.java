package net.stargazer.regenerating_ore_veins;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

public final class VeinLocatorTuningRecipe extends CustomRecipe {
    public VeinLocatorTuningRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return !this.assemble(input, level.registryAccess()).isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack locator = ItemStack.EMPTY;
        Block targetBlock = null;

        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }

            if (stack.is(ModContent.VEIN_LOCATOR.get())) {
                if (!locator.isEmpty()) {
                    return ItemStack.EMPTY;
                }

                locator = stack;
            } else if (stack.getItem() instanceof BlockItem blockItem) {
                if (targetBlock != null) {
                    return ItemStack.EMPTY;
                }

                targetBlock = blockItem.getBlock();
            } else {
                return ItemStack.EMPTY;
            }
        }

        return !locator.isEmpty() && targetBlock != null ? VeinLocatorItem.createAttuned(locator, targetBlock) : ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModContent.VEIN_LOCATOR_TUNING_RECIPE.get();
    }
}
