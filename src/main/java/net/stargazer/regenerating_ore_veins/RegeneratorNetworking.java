package net.stargazer.regenerating_ore_veins;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class RegeneratorNetworking {
    private RegeneratorNetworking() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(UpdateRegeneratorPayload.TYPE, UpdateRegeneratorPayload.STREAM_CODEC, RegeneratorNetworking::handleUpdate);
    }

    private static void handleUpdate(UpdateRegeneratorPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.isCreative()) {
            return;
        }

        if (!(player.level().getBlockEntity(payload.pos()) instanceof RegeneratorBlockEntity blockEntity)) {
            return;
        }

        if (!BuiltInRegistries.BLOCK.containsKey(payload.targetBlock())) {
            player.displayClientMessage(Component.translatable("message.regenerating_ore_veins.invalid_target", payload.targetBlock().toString()), false);
            return;
        }

        Block targetBlock = BuiltInRegistries.BLOCK.get(payload.targetBlock());
        if (targetBlock == Blocks.AIR || targetBlock == ModContent.REGENERATOR_BLOCK.get()) {
            player.displayClientMessage(Component.translatable("message.regenerating_ore_veins.invalid_target", payload.targetBlock().toString()), false);
            return;
        }

        blockEntity.setConfiguration(targetBlock.defaultBlockState(), payload.intervalSeconds(), payload.jitterRangeMinSeconds(), payload.jitterRangeMaxSeconds());
        player.displayClientMessage(Component.translatable("message.regenerating_ore_veins.config_saved"), false);
    }

    public record UpdateRegeneratorPayload(
            BlockPos pos,
            int intervalSeconds,
            int jitterRangeMinSeconds,
            int jitterRangeMaxSeconds,
            ResourceLocation targetBlock
    ) implements CustomPacketPayload {
        public static final Type<UpdateRegeneratorPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(RegeneratingOreVeins.MOD_ID, "update_regenerator"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UpdateRegeneratorPayload> STREAM_CODEC =
                StreamCodec.of(UpdateRegeneratorPayload::write, UpdateRegeneratorPayload::read);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static UpdateRegeneratorPayload read(RegistryFriendlyByteBuf buffer) {
            return new UpdateRegeneratorPayload(
                    buffer.readBlockPos(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readResourceLocation()
            );
        }

        private static void write(RegistryFriendlyByteBuf buffer, UpdateRegeneratorPayload payload) {
            buffer.writeBlockPos(payload.pos());
            buffer.writeVarInt(payload.intervalSeconds());
            buffer.writeVarInt(payload.jitterRangeMinSeconds());
            buffer.writeVarInt(payload.jitterRangeMaxSeconds());
            buffer.writeResourceLocation(payload.targetBlock());
        }
    }
}
