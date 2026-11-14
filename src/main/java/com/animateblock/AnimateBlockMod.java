package com.animateblock;

import com.animateblock.item.AnimateBlockItem;
import com.animateblock.network.SyncTimelinePayload;
import com.animateblock.server.ServerAnimationManager;
import com.animateblock.timeline.AnimationData;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

public class AnimateBlockMod implements ModInitializer {
    public static final String MOD_ID = "animateblock";

    public static final Item ANIMATE_BLOCK_ITEM = Registry.register(
            Registries.ITEM,
            Identifier.of(MOD_ID, "animate_block"),
            new AnimateBlockItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC))
    );

    @Override
    public void onInitialize() {
        // Add to Creative Tools Tab
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(ANIMATE_BLOCK_ITEM));

        // Automatically put AnimateBlock (enchanted wooden axe) in player's inventory on join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            boolean hasItem = false;
            for (int i = 0; i < player.getInventory().size(); i++) {
                if (player.getInventory().getStack(i).isOf(ANIMATE_BLOCK_ITEM)) {
                    hasItem = true;
                    break;
                }
            }
            if (!hasItem) {
                player.getInventory().insertStack(new ItemStack(ANIMATE_BLOCK_ITEM));
            }
        });

        // Register Network Payload
        PayloadTypeRegistry.playC2S().register(SyncTimelinePayload.ID, SyncTimelinePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(SyncTimelinePayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                AnimationData data = AnimationData.fromNbt(payload.nbt());
                ServerAnimationManager.updateOrStart(context.player().getServerWorld(), data, payload.stopAndRestore());
            });
        });

        // Tick server animations
        ServerTickEvents.END_SERVER_TICK.register(ServerAnimationManager::tick);
    }
}
