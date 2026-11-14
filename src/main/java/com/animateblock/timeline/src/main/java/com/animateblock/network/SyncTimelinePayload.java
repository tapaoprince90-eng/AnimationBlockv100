package com.animateblock.network;

import com.animateblock.AnimateBlockMod;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SyncTimelinePayload(NbtCompound nbt, boolean stopAndRestore) implements CustomPayload {
    public static final CustomPayload.Id<SyncTimelinePayload> ID =
            new CustomPayload.Id<>(Identifier.of(AnimateBlockMod.MOD_ID, "sync_timeline"));

    public static final PacketCodec<RegistryByteBuf, SyncTimelinePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.NBT_COMPOUND, SyncTimelinePayload::nbt,
            PacketCodecs.BOOL, SyncTimelinePayload::stopAndRestore,
            SyncTimelinePayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
