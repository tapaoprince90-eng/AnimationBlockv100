package com.animateblock.item;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

public class AnimateBlockItem extends Item {
    public AnimateBlockItem(Settings settings) {
        super(settings);
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return true; // Always looks enchanted
    }

    @Override
    public Text getName(ItemStack stack) {
        return Text.literal("AnimateBlock").formatted(Formatting.AQUA, Formatting.BOLD);
    }

    @Override
    public boolean canMine(BlockState state, World world, BlockPos pos, PlayerEntity miner) {
        return false; // Prevents breaking blocks when left-clicking to open CapCut menu
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.literal("Right-Click block: ").formatted(Formatting.GRAY)
                .append(Text.literal("Select block to animate").formatted(Formatting.YELLOW)));
        tooltip.add(Text.literal("Shift + Right-Click: ").formatted(Formatting.GRAY)
                .append(Text.literal("Copy target Move position").formatted(Formatting.GREEN)));
        tooltip.add(Text.literal("Left-Click (Break): ").formatted(Formatting.GRAY)
                .append(Text.literal("Open CapCut Timeline Menu").formatted(Formatting.AQUA)));
    }
}
