package com.animateblock.timeline;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AnimationData {

    public enum EasingGraph {
        LINEAR("Linear"),
        EASE_IN("Ease In"),
        QUAD_IN("Quad In"),
        CUBIC_IN("Cubic In"),
        EASE_OUT("Ease Out"),
        QUAD_OUT("Quad Out");

        public final String label;

        EasingGraph(String label) {
            this.label = label;
        }

        public double apply(double t) {
            t = Math.max(0.0, Math.min(1.0, t));
            return switch (this) {
                case LINEAR -> t;
                case EASE_IN -> 1.0 - Math.cos((t * Math.PI) / 2.0);
                case QUAD_IN -> t * t;
                case CUBIC_IN -> t * t * t;
                case EASE_OUT -> Math.sin((t * Math.PI) / 2.0);
                case QUAD_OUT -> 1.0 - (1.0 - t) * (1.0 - t);
            };
        }
    }

    public enum EffectType {
        SMOKE("Smoke"),
        SHAKE_BLOCK("ShakeBlock"),
        SMOOTH_SHAKE("SmoothShake"),
        FIRE("Fire"),
        EXPLODE("Explode"),
        FIRE_EXPLODE("FireExplode");

        public final String label;

        EffectType(String label) {
            this.label = label;
        }
    }

    public static class MoveKeyframe {
        public float time; // in seconds (0.0 to duration)
        public double dx, dy, dz; // offset from origin BlockPos
        public EasingGraph graph = EasingGraph.LINEAR;

        public MoveKeyframe(float time, double dx, double dy, double dz, EasingGraph graph) {
            this.time = time;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.graph = graph;
        }
    }

    public static class VisibilityKeyframe {
        public float time;
        public boolean showBlock; // true = ♦-showblock, false = ♦-hideblock

        public VisibilityKeyframe(float time, boolean showBlock) {
            this.time = time;
            this.showBlock = showBlock;
        }
    }

    public static class EffectKeyframe {
        public float time;
        public EffectType effectType;
        public boolean showEffect; // true = ♦ showeffect (start), false = ♦ hideeffect (end)
        public EasingGraph graph = EasingGraph.LINEAR;

        public EffectKeyframe(float time, EffectType effectType, boolean showEffect, EasingGraph graph) {
            this.time = time;
            this.effectType = effectType;
            this.showEffect = showEffect;
            this.graph = graph;
        }
    }

    public static class SoundKeyframe {
        public float time;
        public String soundId;

        public SoundKeyframe(float time, String soundId) {
            this.time = time;
            this.soundId = soundId;
        }
    }

    public BlockPos originPos;
    public float duration = 10.0f; // 10.0 seconds default
    public boolean playing = false;
    public boolean loop = true;
    public float currentTime = 0.0f;

    public final List<MoveKeyframe> moveKeyframes = new ArrayList<>();
    public final List<VisibilityKeyframe> visibilityKeyframes = new ArrayList<>();
    public final List<EffectKeyframe> effectKeyframes = new ArrayList<>();
    public final List<SoundKeyframe> soundKeyframes = new ArrayList<>();

    public AnimationData(BlockPos originPos) {
        this.originPos = originPos;
        // Start with default keyframe at 0.0s
        this.moveKeyframes.add(new MoveKeyframe(0.0f, 0.0, 0.0, 0.0, EasingGraph.LINEAR));
        this.visibilityKeyframes.add(new VisibilityKeyframe(0.0f, true));
    }

    public void sortAll() {
        moveKeyframes.sort(Comparator.comparingDouble(k -> k.time));
        visibilityKeyframes.sort(Comparator.comparingDouble(k -> k.time));
        effectKeyframes.sort(Comparator.comparingDouble(k -> k.time));
        soundKeyframes.sort(Comparator.comparingDouble(k -> k.time));
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt("ox", originPos.getX());
        nbt.putInt("oy", originPos.getY());
        nbt.putInt("oz", originPos.getZ());
        nbt.putFloat("duration", duration);
        nbt.putBoolean("playing", playing);
        nbt.putBoolean("loop", loop);
        nbt.putFloat("currentTime", currentTime);

        NbtList moveList = new NbtList();
        for (MoveKeyframe k : moveKeyframes) {
            NbtCompound c = new NbtCompound();
            c.putFloat("t", k.time);
            c.putDouble("dx", k.dx);
            c.putDouble("dy", k.dy);
            c.putDouble("dz", k.dz);
            c.putString("g", k.graph.name());
            moveList.add(c);
        }
        nbt.put("moves", moveList);

        NbtList visList = new NbtList();
        for (VisibilityKeyframe k : visibilityKeyframes) {
            NbtCompound c = new NbtCompound();
            c.putFloat("t", k.time);
            c.putBoolean("show", k.showBlock);
            visList.add(c);
        }
        nbt.put("vis", visList);

        NbtList effList = new NbtList();
        for (EffectKeyframe k : effectKeyframes) {
            NbtCompound c = new NbtCompound();
            c.putFloat("t", k.time);
            c.putString("type", k.effectType.name());
            c.putBoolean("show", k.showEffect);
            c.putString("g", k.graph.name());
            effList.add(c);
        }
        nbt.put("effects", effList);

        NbtList sndList = new NbtList();
        for (SoundKeyframe k : soundKeyframes) {
            NbtCompound c = new NbtCompound();
            c.putFloat("t", k.time);
            c.putString("id", k.soundId);
            sndList.add(c);
        }
        nbt.put("sounds", sndList);
        return nbt;
    }

    public static AnimationData fromNbt(NbtCompound nbt) {
        BlockPos pos = new BlockPos(nbt.getInt("ox"), nbt.getInt("oy"), nbt.getInt("oz"));
        AnimationData data = new AnimationData(pos);
        data.moveKeyframes.clear();
        data.visibilityKeyframes.clear();
        data.effectKeyframes.clear();
        data.soundKeyframes.clear();

        data.duration = nbt.getFloat("duration");
        if (data.duration <= 0.5f) data.duration = 10.0f;
        data.playing = nbt.getBoolean("playing");
        data.loop = nbt.getBoolean("loop");
        data.currentTime = nbt.getFloat("currentTime");

        NbtList moveList = nbt.getList("moves", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < moveList.size(); i++) {
            NbtCompound c = moveList.getCompound(i);
            EasingGraph g = EasingGraph.LINEAR;
            try { g = EasingGraph.valueOf(c.getString("g")); } catch (Exception ignored) {}
            data.moveKeyframes.add(new MoveKeyframe(c.getFloat("t"), c.getDouble("dx"), c.getDouble("dy"), c.getDouble("dz"), g));
        }

        NbtList visList = nbt.getList("vis", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < visList.size(); i++) {
            NbtCompound c = visList.getCompound(i);
            data.visibilityKeyframes.add(new VisibilityKeyframe(c.getFloat("t"), c.getBoolean("show")));
        }

        NbtList effList = nbt.getList("effects", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < effList.size(); i++) {
            NbtCompound c = effList.getCompound(i);
            EffectType type = EffectType.SMOKE;
            try { type = EffectType.valueOf(c.getString("type")); } catch (Exception ignored) {}
            EasingGraph g = EasingGraph.LINEAR;
            try { g = EasingGraph.valueOf(c.getString("g")); } catch (Exception ignored) {}
            data.effectKeyframes.add(new EffectKeyframe(c.getFloat("t"), type, c.getBoolean("show"), g));
        }

        NbtList sndList = nbt.getList("sounds", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < sndList.size(); i++) {
            NbtCompound c = sndList.getCompound(i);
            data.soundKeyframes.add(new SoundKeyframe(c.getFloat("t"), c.getString("id")));
        }

        data.sortAll();
        return data;
    }

    public double[] evaluatePositionOffset(float time) {
        if (moveKeyframes.isEmpty()) return new double[]{0, 0, 0};
        if (time <= moveKeyframes.getFirst().time) {
            MoveKeyframe first = moveKeyframes.getFirst();
            return new double[]{first.dx, first.dy, first.dz};
        }
        if (time >= moveKeyframes.getLast().time) {
            MoveKeyframe last = moveKeyframes.getLast();
            return new double[]{last.dx, last.dy, last.dz};
        }
        for (int i = 0; i < moveKeyframes.size() - 1; i++) {
            MoveKeyframe a = moveKeyframes.get(i);
            MoveKeyframe b = moveKeyframes.get(i + 1);
            if (time >= a.time && time <= b.time) {
                double span = Math.max(0.0001, b.time - a.time);
                double rawT = (time - a.time) / span;
                double easedT = b.graph.apply(rawT);
                return new double[]{
                        a.dx + (b.dx - a.dx) * easedT,
                        a.dy + (b.dy - a.dy) * easedT,
                        a.dz + (b.dz - a.dz) * easedT
                };
            }
        }
        return new double[]{0, 0, 0};
    }

    public boolean evaluateVisibility(float time) {
        boolean visible = true;
        for (VisibilityKeyframe k : visibilityKeyframes) {
            if (k.time <= time + 0.001f) {
                visible = k.showBlock;
            } else {
                break;
            }
        }
        return visible;
    }

    public boolean isEffectActive(EffectType type, float time) {
        boolean active = false;
        for (EffectKeyframe k : effectKeyframes) {
            if (k.effectType == type) {
                if (k.time <= time + 0.001f) {
                    active = k.showEffect;
                } else {
                    break;
                }
            }
        }
        return active;
    }
    }
