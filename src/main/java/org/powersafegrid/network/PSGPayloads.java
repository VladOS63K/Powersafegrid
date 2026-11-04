package org.powersafegrid.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.powersafegrid.PowerSafeGrid;
import org.powersafegrid.electric.VoltageRelayBlockEntity;

public class PSGPayloads {
    public record SetRelayRangePayload(BlockPos pos, float low, float high) implements CustomPacketPayload {
        public static final Type<SetRelayRangePayload> TYPE = new Type<>(PowerSafeGrid.asResource("set_relay_range"));
        public static final StreamCodec<ByteBuf, SetRelayRangePayload> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetRelayRangePayload::pos,
                ByteBufCodecs.FLOAT, SetRelayRangePayload::low,
                ByteBufCodecs.FLOAT, SetRelayRangePayload::high,
                SetRelayRangePayload::new);

        public void handle(ServerPlayer sender) {
            if (sender == null || sender.isSpectator())
                return;
            var level = sender.serverLevel();
            if (sender.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64)
                return;
            if (level.getBlockEntity(pos) instanceof VoltageRelayBlockEntity relay)
                relay.setRange(low, high);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void sendToServer(CustomPacketPayload payload) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(payload);
    }

    public static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> {
            PayloadRegistrar registrar = event.registrar("1");
            registrar.playToServer(SetRelayRangePayload.TYPE, SetRelayRangePayload.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() -> payload.handle(
                            context.player() instanceof ServerPlayer sp ? sp : null)));
        });
    }
}
