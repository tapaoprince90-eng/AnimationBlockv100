package com.animateblock.client;

import com.animateblock.AnimateBlockMod;
import com.animateblock.client.gui.CapCutScreen;
import com.animateblock.timeline.AnimationData;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

public class AnimateBlockClient implements ClientModInitializer {

    public static BlockPos selectedBlockPos = null;
    public static BlockPos copiedMoveTargetPos = null;
    private static final Map<BlockPos, AnimationData> TIMELINES = new HashMap<>();

    public static AnimationData getOrCreateTimeline(BlockPos pos) {
        return TIMELINES.computeIfAbsent(pos.toImmutable(), AnimationData::new);
    }

    @Override
    public void onInitializeClient() {
        // Right-Click Block with AnimateBlock Axe -> Selects block to animate (or Shift+Right-Click sets target destination)
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            if (!player.getMainHandStack().isOf(AnimateBlockMod.ANIMATE_BLOCK_ITEM)) return ActionResult.PASS;

            BlockPos clicked = hitResult.getBlockPos().toImmutable();
            if (world.isClient) {
                if (player.isSneaking() && selectedBlockPos != null) {
                    copiedMoveTargetPos = clicked.up();
                    int dx = copiedMoveTargetPos.getX() - selectedBlockPos.getX();
                    int dy = copiedMoveTargetPos.getY() - selectedBlockPos.getY();
                    int dz = copiedMoveTargetPos.getZ() - selectedBlockPos.getZ();
                    player.sendMessage(Text.literal("♦ Copied Move Offset: (" + dx + ", " + dy + ", " + dz + ")! Left-click to open CapCut Menu.")
                            .formatted(Formatting.GREEN), true);
                } else {
                    selectedBlockPos = clicked;
                    getOrCreateTimeline(selectedBlockPos);
                    player.sendMessage(Text.literal("♦ Selected Block at (" + clicked.toShortString() + ")! Left-Click (Break) to open CapCut Menu.")
                            .formatted(Formatting.AQUA), true);
                }
            }
            return ActionResult.SUCCESS;
        });

        // Left-Click (Break) Block with AnimateBlock Axe -> Opens the CapCut Timeline GUI
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!player.getMainHandStack().isOf(AnimateBlockMod.ANIMATE_BLOCK_ITEM)) return ActionResult.PASS;

            if (world.isClient) {
                if (selectedBlockPos == null) {
                    selectedBlockPos = pos.toImmutable();
                }
                AnimationData data = getOrCreateTimeline(selectedBlockPos);
                MinecraftClient.getInstance().execute(() ->
                        MinecraftClient.getInstance().setScreen(new CapCutScreen(data))
                );
            }
            return ActionResult.FAIL; // Cancel block break
        });
    }
}
