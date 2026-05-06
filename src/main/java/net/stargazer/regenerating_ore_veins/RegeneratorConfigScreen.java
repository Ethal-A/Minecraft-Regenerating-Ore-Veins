package net.stargazer.regenerating_ore_veins;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;

public final class RegeneratorConfigScreen extends Screen {
    private static final int PANEL_WIDTH = 260;
    private final BlockPos pos;
    private final String initialTarget;
    private final int initialInterval;
    private final int initialJitterMin;
    private final int initialJitterMax;
    private EditBox targetBox;
    private EditBox intervalBox;
    private EditBox jitterMinBox;
    private EditBox jitterMaxBox;
    private Component status = Component.empty();

    public RegeneratorConfigScreen(BlockPos pos, RegeneratorBlockEntity blockEntity) {
        super(Component.translatable("screen.regenerating_ore_veins.regenerator_config"));
        this.pos = pos.immutable();
        this.initialTarget = blockEntity.hasValidTarget() ? blockEntity.getTargetId().toString() : "minecraft:stone";
        this.initialInterval = blockEntity.getIntervalSeconds();
        this.initialJitterMin = blockEntity.getJitterRangeMinSeconds();
        this.initialJitterMax = blockEntity.getJitterRangeMaxSeconds();
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = Math.max(20, (this.height - 170) / 2);
        this.targetBox = new EditBox(this.font, left, top + 34, PANEL_WIDTH, 20, Component.translatable("screen.regenerating_ore_veins.target_block"));
        this.targetBox.setValue(this.initialTarget);
        this.intervalBox = integerBox(left, top + 72, Integer.toString(this.initialInterval));
        this.jitterMinBox = integerBox(left, top + 110, Integer.toString(this.initialJitterMin));
        this.jitterMaxBox = integerBox(left + 135, top + 110, Integer.toString(this.initialJitterMax));
        this.jitterMinBox.setWidth(125);
        this.jitterMaxBox.setWidth(125);

        this.addRenderableWidget(this.targetBox);
        this.addRenderableWidget(this.intervalBox);
        this.addRenderableWidget(this.jitterMinBox);
        this.addRenderableWidget(this.jitterMaxBox);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.save())
                .bounds(left, top + 145, 125, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> this.onClose())
                .bounds(left + 135, top + 145, 125, 20)
                .build());
        this.setInitialFocus(this.targetBox);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = Math.max(20, (this.height - 170) / 2);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, top, 0xFFFFFF);
        guiGraphics.drawString(this.font, Component.translatable("screen.regenerating_ore_veins.target_block"), left, top + 22, 0xA0A0A0, false);
        guiGraphics.drawString(this.font, Component.translatable("screen.regenerating_ore_veins.interval_seconds"), left, top + 60, 0xA0A0A0, false);
        guiGraphics.drawString(this.font, Component.translatable("screen.regenerating_ore_veins.jitter_min"), left, top + 98, 0xA0A0A0, false);
        guiGraphics.drawString(this.font, Component.translatable("screen.regenerating_ore_veins.jitter_max"), left + 135, top + 98, 0xA0A0A0, false);
        guiGraphics.drawCenteredString(this.font, this.status, this.width / 2, top + 132, 0xFF6666);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private EditBox integerBox(int x, int y, String value) {
        EditBox box = new EditBox(this.font, x, y, PANEL_WIDTH, 20, Component.empty());
        box.setFilter(text -> text.isEmpty() || text.matches("-?\\d{0,9}"));
        box.setValue(value);
        return box;
    }

    private void save() {
        ResourceLocation target;
        int interval;
        int jitterMin;
        int jitterMax;
        try {
            target = ResourceLocation.parse(this.targetBox.getValue().trim());
            interval = Math.max(1, Integer.parseInt(this.intervalBox.getValue().trim()));
            jitterMin = parseOptionalInt(this.jitterMinBox.getValue());
            jitterMax = parseOptionalInt(this.jitterMaxBox.getValue());
        } catch (RuntimeException exception) {
            this.status = Component.translatable("message.regenerating_ore_veins.invalid_config");
            return;
        }

        if (!BuiltInRegistries.BLOCK.containsKey(target) || BuiltInRegistries.BLOCK.get(target) == Blocks.AIR) {
            this.status = Component.translatable("message.regenerating_ore_veins.invalid_target", target.toString());
            return;
        }

        PacketDistributor.sendToServer(new RegeneratorNetworking.UpdateRegeneratorPayload(this.pos, interval, jitterMin, jitterMax, target));
        this.onClose();
    }

    private static int parseOptionalInt(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? 0 : Integer.parseInt(trimmed);
    }
}
