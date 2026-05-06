package net.stargazer.regenerating_ore_veins;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class RegeneratorClient {
    private RegeneratorClient() {
    }

    public static void openConfigScreen(BlockPos pos, RegeneratorBlockEntity blockEntity) {
        Minecraft.getInstance().setScreen(new RegeneratorConfigScreen(pos, blockEntity));
    }
}
