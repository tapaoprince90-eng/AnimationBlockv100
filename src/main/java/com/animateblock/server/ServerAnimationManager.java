package com.animateblock.server;

import com.animateblock.timeline.AnimationData;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class ServerAnimationManager {

    private static class ActiveBlockAnimation {
        final ServerWorld world;
        final BlockPos originPos;
        final BlockState originalState;
        AnimationData data;
        UUID displayEntityUuid;
        float prevTime = -1.0f;
        boolean currentlyVisible = true;

        ActiveBlockAnimation(ServerWorld world, BlockPos originPos, BlockState originalState, AnimationData data) {
            this.world = world;
            this.originPos = originPos;
            this.originalState = originalState;
            this.data = data;
        }
    }

    private static final Map<BlockPos, ActiveBlockAnimation> ACTIVE = new HashMap<>();

    public static void updateOrStart(ServerWorld world, AnimationData incoming, boolean stopAndRestore) {
        BlockPos pos = incoming.originPos;
        ActiveBlockAnimation existing = ACTIVE.get(pos);

        if (stopAndRestore) {
            if (existing != null) {
                restoreBlock(existing);
                ACTIVE.remove(pos);
            }
            return;
        }

        if (existing == null) {
            BlockState currentWorldState = world.getBlockState(pos);
            if (currentWorldState.isAir()) {
                currentWorldState = Blocks.OAK_PLANKS.getDefaultState();
            }
            existing = new ActiveBlockAnimation(world, pos, currentWorldState, incoming);
            ACTIVE.put(pos, existing);
            // Hide static block and spawn smooth BlockDisplayEntity
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
            spawnDisplayEntity(existing);
        } else {
            existing.data = incoming;
        }

        applyFrame(existing, incoming.currentTime, false);
    }

    private static void spawnDisplayEntity(ActiveBlockAnimation anim) {
        DisplayEntity.BlockDisplayEntity display = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, anim.world);
        display.setPosition(anim.originPos.getX(), anim.originPos.getY(), anim.originPos.getZ());
        NbtCompound nbt = new NbtCompound();
        display.writeNbt(nbt);
        nbt.put("block_state", NbtHelper.fromBlockState(anim.originalState));
        nbt.putInt("teleport_duration", 1);
        display.readNbt(nbt);
        display.setPosition(anim.originPos.getX(), anim.originPos.getY(), anim.originPos.getZ());
        anim.world.spawnEntity(display);
        anim.displayEntityUuid = display.getUuid();
    }

    private static DisplayEntity.BlockDisplayEntity getDisplay(ActiveBlockAnimation anim) {
        if (anim.displayEntityUuid == null) return null;
        Entity e = anim.world.getEntity(anim.displayEntityUuid);
        if (e instanceof DisplayEntity.BlockDisplayEntity bde) {
            return bde;
        }
        return null;
    }

    private static void restoreBlock(ActiveBlockAnimation anim) {
        DisplayEntity.BlockDisplayEntity display = getDisplay(anim);
        if (display != null) {
            display.discard();
        }
        anim.world.setBlockState(anim.originPos, anim.originalState, 3);
    }

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<BlockPos, ActiveBlockAnimation>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            ActiveBlockAnimation anim = it.next().getValue();
            if (anim.data.playing) {
                float prev = anim.data.currentTime;
                anim.data.currentTime += 0.05f; // 20 ticks = 1.0s
                if (anim.data.currentTime > anim.data.duration) {
                    if (anim.data.loop) {
                        anim.data.currentTime = 0.0f;
                        anim.prevTime = -1.0f;
                    } else {
                        anim.data.currentTime = anim.data.duration;
                        anim.data.playing = false;
                    }
                }
                applyFrame(anim, anim.data.currentTime, true);
                anim.prevTime = prev;
            }
        }
    }

    private static void applyFrame(ActiveBlockAnimation anim, float time, boolean triggerEvents) {
        DisplayEntity.BlockDisplayEntity display = getDisplay(anim);
        if (display == null) {
            spawnDisplayEntity(anim);
            display = getDisplay(anim);
            if (display == null) return;
        }

        // 1. Evaluate Visibility (♦-showblock / ♦-hideblock)
        boolean visible = anim.data.evaluateVisibility(time);
        if (visible != anim.currentlyVisible) {
            anim.currentlyVisible = visible;
            NbtCompound nbt = new NbtCompound();
            display.writeNbt(nbt);
            nbt.put("block_state", NbtHelper.fromBlockState(visible ? anim.originalState : Blocks.AIR.getDefaultState()));
            display.readNbt(nbt);
        }

        // 2. Evaluate Position Offset + Easing Graph (♦ movetheblock)
        double[] offset = anim.data.evaluatePositionOffset(time);
        double x = anim.originPos.getX() + offset[0];
        double y = anim.originPos.getY() + offset[1];
        double z = anim.originPos.getZ() + offset[2];

        Random rng = anim.world.getRandom();

        // 3. Evaluate Shake Effects (♦ ShakeBlock ♦ / ♦ SmoothShake ♦)
        if (anim.data.isEffectActive(AnimationData.EffectType.SHAKE_BLOCK, time)) {
            x += (rng.nextDouble() - 0.5) * 0.35;
            y += (rng.nextDouble() - 0.5) * 0.35;
            z += (rng.nextDouble() - 0.5) * 0.35;
        }
        if (anim.data.isEffectActive(AnimationData.EffectType.SMOOTH_SHAKE, time)) {
            x += Math.sin(time * 18.0) * 0.18;
            y += Math.cos(time * 14.0) * 0.12;
            z += Math.sin(time * 15.0 + 1.2) * 0.18;
        }

        display.requestTeleport(x, y, z);

        double cx = x + 0.5;
        double cy = y + 0.5;
        double cz = z + 0.5;

        // 4. Evaluate Particle Effects (Smoke, Fire, Explode, FireExplode)
        if (anim.data.isEffectActive(AnimationData.EffectType.SMOKE, time)) {
            anim.world.spawnParticles(ParticleTypes.LARGE_SMOKE, cx, cy, cz, 4, 0.3, 0.3, 0.3, 0.02);
            anim.world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, cx, cy + 0.3, cz, 2, 0.2, 0.2, 0.2, 0.01);
        }
        if (anim.data.isEffectActive(AnimationData.EffectType.FIRE, time)) {
            anim.world.spawnParticles(ParticleTypes.FLAME, cx, cy, cz, 6, 0.35, 0.35, 0.35, 0.03);
            anim.world.spawnParticles(ParticleTypes.LAVA, cx, cy, cz, 1, 0.2, 0.2, 0.2, 0.0);
        }
        if (anim.data.isEffectActive(AnimationData.EffectType.EXPLODE, time)) {
            anim.world.spawnParticles(ParticleTypes.EXPLOSION, cx, cy, cz, 2, 0.3, 0.3, 0.3, 0.05);
            anim.world.spawnParticles(ParticleTypes.POOF, cx, cy, cz, 5, 0.4, 0.4, 0.4, 0.05);
        }
        if (anim.data.isEffectActive(AnimationData.EffectType.FIRE_EXPLODE, time)) {
            anim.world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, cx, cy, cz, 1, 0.0, 0.0, 0.0, 0.0);
            anim.world.spawnParticles(ParticleTypes.FLAME, cx, cy, cz, 12, 0.5, 0.5, 0.5, 0.08);
            anim.world.spawnParticles(ParticleTypes.LAVA, cx, cy, cz, 4, 0.4, 0.4, 0.4, 0.05);
        }

        // 5. Evaluate Sound Keyframes (♦ Sound ♦)
        if (triggerEvents) {
            float windowStart = anim.prevTime < 0 ? time - 0.05f : anim.prevTime;
            for (AnimationData.SoundKeyframe sk : anim.data.soundKeyframes) {
                if (sk.time > windowStart && sk.time <= time + 0.001f) {
                    Identifier id = Identifier.tryParse(sk.soundId);
                    if (id != null) {
                        SoundEvent event = Registries.SOUND_EVENT.get(id);
                        if (event != null) {
                            anim.world.playSound(null, BlockPos.ofFloored(cx, cy, cz), event, SoundCategory.BLOCKS, 1.0f, 1.0f);
                        }
                    }
                }
            }
        }
    }
}
