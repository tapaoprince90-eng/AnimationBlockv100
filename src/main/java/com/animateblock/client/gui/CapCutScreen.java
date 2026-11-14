package com.animateblock.client.gui;

import com.animateblock.client.AnimateBlockClient;
import com.animateblock.network.SyncTimelinePayload;
import com.animateblock.timeline.AnimationData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Stack;

public class CapCutScreen extends Screen {

    private enum SubPanel {
        NONE,
        MOVE_EDIT,
        EFFECTS,
        SOUNDS,
        GRAPHS
    }

    private final AnimationData data;
    private SubPanel activePanel = SubPanel.NONE;

    // Undo / Redo history stacks
    private final Stack<NbtCompound> undoStack = new Stack<>();
    private final Stack<NbtCompound> redoStack = new Stack<>();

    // Pending Move Offset values for "+♦"
    private double editDx = 0.0;
    private double editDy = 1.0;
    private double editDz = 0.0;
    private AnimationData.EasingGraph selectedGraph = AnimationData.EasingGraph.LINEAR;
    private AnimationData.EffectType selectedEffect = AnimationData.EffectType.SMOOTH_SHAKE;

    // Sound Search Browser
    private TextFieldWidget soundSearchField;
    private final List<String> allSoundIds = new ArrayList<>();
    private final List<String> filteredSoundIds = new ArrayList<>();
    private int soundScrollOffset = 0;

    private boolean draggingPlayhead = false;

    public CapCutScreen(AnimationData data) {
        super(Text.literal("CapCut Block Animator"));
        this.data = data;

        // If player Shift+Right-Clicked a target position in-world, prefill Move offset
        if (AnimateBlockClient.copiedMoveTargetPos != null && data.originPos != null) {
            BlockPos diff = AnimateBlockClient.copiedMoveTargetPos.subtract(data.originPos);
            this.editDx = diff.getX();
            this.editDy = diff.getY();
            this.editDz = diff.getZ();
        }

        for (Identifier id : Registries.SOUND_EVENT.getIds()) {
            allSoundIds.add(id.toString());
        }
        allSoundIds.sort(String::compareTo);
        filteredSoundIds.addAll(allSoundIds);
    }

    @Override
    protected void init() {
        super.init();
        soundSearchField = new TextFieldWidget(this.textRenderer, this.width / 2 - 110, 42, 220, 16, Text.literal("Search sound..."));
        soundSearchField.setMaxLength(100);
        soundSearchField.setSuggestion("Search sound (e.g. explode, note, bell)...");
        soundSearchField.setChangedListener(query -> {
            soundSearchField.setSuggestion(query.isEmpty() ? "Search sound (e.g. explode, note, bell)..." : "");
            filterSounds(query);
        });
        this.addSelectableChild(soundSearchField);
        syncToServer(false);
    }

    private void filterSounds(String query) {
        filteredSoundIds.clear();
        String lower = query.toLowerCase(Locale.ROOT).trim();
        for (String id : allSoundIds) {
            if (lower.isEmpty() || id.toLowerCase(Locale.ROOT).contains(lower)) {
                filteredSoundIds.add(id);
            }
        }
        soundScrollOffset = 0;
    }

    private void pushUndoState() {
        undoStack.push(data.toNbt());
        if (undoStack.size() > 30) undoStack.removeFirst();
        redoStack.clear();
    }

    private void undo() {
        if (!undoStack.isEmpty()) {
            redoStack.push(data.toNbt());
            NbtCompound prev = undoStack.pop();
            restoreFromNbt(prev);
            syncToServer(false);
        }
    }

    private void redo() {
        if (!redoStack.isEmpty()) {
            undoStack.push(data.toNbt());
            NbtCompound next = redoStack.pop();
            restoreFromNbt(next);
            syncToServer(false);
        }
    }

    private void restoreFromNbt(NbtCompound nbt) {
        AnimationData restored = AnimationData.fromNbt(nbt);
        data.moveKeyframes.clear();
        data.moveKeyframes.addAll(restored.moveKeyframes);
        data.visibilityKeyframes.clear();
        data.visibilityKeyframes.addAll(restored.visibilityKeyframes);
        data.effectKeyframes.clear();
        data.effectKeyframes.addAll(restored.effectKeyframes);
        data.soundKeyframes.clear();
        data.soundKeyframes.addAll(restored.soundKeyframes);
    }

    private void syncToServer(boolean stopAndRestore) {
        data.sortAll();
        ClientPlayNetworking.send(new SyncTimelinePayload(data.toNbt(), stopAndRestore));
    }

    @Override
    public void tick() {
        super.tick();
        if (data.playing) {
            data.currentTime += 0.05f;
            if (data.currentTime > data.duration) {
                if (data.loop) {
                    data.currentTime = 0.0f;
                } else {
                    data.currentTime = data.duration;
                    data.playing = false;
                }
            }
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Intentionally empty so the top half of the screen stays crystal-clear to preview the block in-world
    }

    @Override
    public boolean shouldPause() {
        return false; // Keep world running so particles, sounds, and block movement play live
    }

    private int getTimelineLeft() {
        return 48;
    }

    private int getTimelineRight() {
        return this.width - 20;
    }

    private int timeToX(float t) {
        int left = getTimelineLeft();
        int right = getTimelineRight();
        return left + Math.round((t / data.duration) * (right - left));
    }

    private float xToTime(double mouseX) {
        int left = getTimelineLeft();
        int right = getTimelineRight();
        float norm = (float) ((mouseX - left) / (double) Math.max(1, right - left));
        norm = Math.max(0.0f, Math.min(1.0f, norm));
        return Math.round(norm * data.duration * 20.0f) / 20.0f;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int panelTop = this.height - 142;
        int transportY = panelTop - 22;

        // 1. Transport Bar (Image 2: Fullscreen/Reset on left, Play ▶ in center, +♦, Undo ↶, Redo ↷ on right)
        context.fill(0, transportY, this.width, panelTop, 0xE6101014);
        context.fill(0, transportY, this.width, transportY + 1, 0xFF00BCD4); // Cyan top border like Image 2

        // Left button: [Reset Block]
        drawButton(context, 8, transportY + 3, 56, 16, "[Reset]", 0xFF23232B, mouseX, mouseY);
        // Time readout
        String timeStr = String.format(Locale.US, "%.1fs / %.1fs", data.currentTime, data.duration);
        context.drawTextWithShadow(this.textRenderer, timeStr, 70, transportY + 7, 0xFFCCCCCC);

        // Center button: Play / Pause ▶
        String playSymbol = data.playing ? "⏸" : "▶";
        drawButton(context, this.width / 2 - 16, transportY + 2, 32, 18, playSymbol, data.playing ? 0xFF00838F : 0xFF282832, mouseX, mouseY);

        // Right buttons: +♦ (Add Keyframe), ↶ (Undo), ↷ (Redo)
        drawButton(context, this.width - 116, transportY + 3, 34, 16, "+♦", 0xFF00ACC1, mouseX, mouseY);
        drawButton(context, this.width - 76, transportY + 3, 30, 16, "↶", undoStack.isEmpty() ? 0xFF1A1A20 : 0xFF2E2E38, mouseX, mouseY);
        drawButton(context, this.width - 40, transportY + 3, 30, 16, "↷", redoStack.isEmpty() ? 0xFF1A1A20 : 0xFF2E2E38, mouseX, mouseY);

        // 2. Main CapCut Timeline Background (Image 1)
        context.fill(0, panelTop, this.width, this.height, 0xF216161A);

        int tlLeft = getTimelineLeft();
        int tlRight = getTimelineRight();

        // Track 1: Block Movement & Visibility Track
        int track1Y = panelTop + 8;
        context.fill(8, track1Y, 40, track1Y + 24, 0xFF25252D);
        context.drawCenteredTextWithShadow(this.textRenderer, "BLK", 24, track1Y + 8, 0xFF00E5FF);
        context.fill(tlLeft, track1Y, tlRight, track1Y + 24, 0xFF262630);
        context.drawBorder(tlLeft, track1Y, tlRight - tlLeft, 24, 0xFF3E3E4C);

        // Draw "♦ ─── movetheblock (Graph) ─── ♦" spans on Track 1
        for (int i = 0; i < data.moveKeyframes.size() - 1; i++) {
            AnimationData.MoveKeyframe a = data.moveKeyframes.get(i);
            AnimationData.MoveKeyframe b = data.moveKeyframes.get(i + 1);
            int x1 = timeToX(a.time);
            int x2 = timeToX(b.time);
            context.fill(x1, track1Y + 4, x2, track1Y + 20, 0x6600ACC1);
            String spanLabel = "movetheblock [" + b.graph.label + "]";
            if (x2 - x1 > 45) {
                context.drawCenteredTextWithShadow(this.textRenderer, spanLabel, (x1 + x2) / 2, track1Y + 8, 0xFFE0F7FA);
            }
        }
        // Draw Move Keyframe Diamonds ♦
        for (AnimationData.MoveKeyframe k : data.moveKeyframes) {
            int kx = timeToX(k.time);
            drawDiamond(context, kx, track1Y + 12, 0xFFFF5252);
        }
        // Draw ShowBlock / HideBlock Diamonds (♦-showblock / ♦-hideblock)
        for (AnimationData.VisibilityKeyframe vk : data.visibilityKeyframes) {
            int kx = timeToX(vk.time);
            drawDiamond(context, kx, track1Y + 4, vk.showBlock ? 0xFF69F0AE : 0xFFFFAB40);
            String tag = vk.showBlock ? "♦show" : "♦hide";
            context.drawTextWithShadow(this.textRenderer, tag, kx + 4, track1Y + 1, vk.showBlock ? 0xFF69F0AE : 0xFFFFAB40);
        }

        // Track 2: Audio / Sound Track ("♪  + Add audio" -> "♦   Sound   ♦")
        int track2Y = track1Y + 30;
        context.fill(8, track2Y, 40, track2Y + 22, 0xFF25252D);
        context.drawCenteredTextWithShadow(this.textRenderer, "♪", 24, track2Y + 7, 0xFFCCCCCC);
        context.fill(tlLeft, track2Y, tlRight, track2Y + 22, 0xFF202028);
        if (data.soundKeyframes.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, "+ Add audio (Click SOUND)", tlLeft + 8, track2Y + 7, 0xFF777785);
        } else {
            for (AnimationData.SoundKeyframe sk : data.soundKeyframes) {
                int sx = timeToX(sk.time);
                int boxW = 68;
                int endX = Math.min(tlRight, sx + boxW);
                context.fill(sx, track2Y + 3, endX, track2Y + 19, 0x883949AB);
                drawDiamond(context, sx, track2Y + 11, 0xFF82B1FF);
                drawDiamond(context, endX, track2Y + 11, 0xFF82B1FF);
                String shortName = sk.soundId.replace("minecraft:", "");
                if (shortName.length() > 10) shortName = shortName.substring(0, 10);
                context.drawCenteredTextWithShadow(this.textRenderer, shortName, (sx + endX) / 2, track2Y + 7, 0xFFFFFFFF);
            }
        }

        // Track 3: Effect Track ("★  + Add effect" -> "♦   smootheffect   ♦")
        int track3Y = track2Y + 26;
        context.fill(8, track3Y, 40, track3Y + 22, 0xFF25252D);
        context.drawCenteredTextWithShadow(this.textRenderer, "★", 24, track3Y + 7, 0xFFFFD54F);
        context.fill(tlLeft, track3Y, tlRight, track3Y + 22, 0xFF202028);
        if (data.effectKeyframes.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, "+ Add effect (Smoke, ShakeBlock, SmoothShake, Fire, Explode)", tlLeft + 8, track3Y + 7, 0xFF777785);
        } else {
            for (int i = 0; i < data.effectKeyframes.size(); i++) {
                AnimationData.EffectKeyframe ek = data.effectKeyframes.get(i);
                int x1 = timeToX(ek.time);
                if (ek.showEffect) {
                    // Find matching hideeffect ♦
                    int x2 = tlRight;
                    for (int j = i + 1; j < data.effectKeyframes.size(); j++) {
                        AnimationData.EffectKeyframe next = data.effectKeyframes.get(j);
                        if (next.effectType == ek.effectType && !next.showEffect) {
                            x2 = timeToX(next.time);
                            break;
                        }
                    }
                    context.fill(x1, track3Y + 3, x2, track3Y + 19, 0x886A1B9A);
                    context.drawCenteredTextWithShadow(this.textRenderer, "♦ " + ek.effectType.label + " ♦", (x1 + x2) / 2, track3Y + 7, 0xFFFFF59D);
                }
                drawDiamond(context, x1, track3Y + 11, ek.showEffect ? 0xFFE040FB : 0xFFFF5252);
            }
        }

        // 3. White Vertical Playhead Line (Image 1)
        int playheadX = timeToX(data.currentTime);
        context.fill(playheadX - 1, panelTop + 2, playheadX + 1, panelTop + 92, 0xFFFFFFFF);
        context.fill(playheadX - 4, panelTop + 2, playheadX + 4, panelTop + 6, 0xFFFFFFFF);

        // 4. Bottom CapCut Toolbar Buttons (Image 1)
        int barY = this.height - 34;
        context.fill(0, barY - 4, this.width, this.height, 0xFF101014);
        int btnW = (this.width - 24) / 7;
        String[] labels = {"< Close", "♦ MovePos", "♦ Show/Hide", "Graphs", "EFFECT", "SOUND", "Delete ♦"};
        SubPanel[] targets = {null, SubPanel.MOVE_EDIT, null, SubPanel.GRAPHS, SubPanel.EFFECTS, SubPanel.SOUNDS, null};

        for (int i = 0; i < labels.length; i++) {
            int bx = 8 + i * (btnW + 2);
            boolean active = targets[i] != null && activePanel == targets[i];
            int color = active ? 0xFF00ACC1 : 0xFF23232C;
            drawButton(context, bx, barY, btnW, 24, labels[i], color, mouseX, mouseY);
        }

        // 5. Render Active Sub-Panel (Move Editor, Effects, Sounds Search, or Image 3 Graphs!)
        renderSubPanel(context, mouseX, mouseY, transportY);

        super.render(context, mouseX, mouseY, delta);
    }

    private void renderSubPanel(DrawContext context, int mouseX, int mouseY, int transportY) {
        if (activePanel == SubPanel.NONE) return;

        if (activePanel == SubPanel.GRAPHS) {
            // Image 3: Graphs Transition Curve Selector
            int boxTop = transportY - 82;
            context.fill(0, boxTop, this.width, transportY, 0xF418181E);
            context.drawCenteredTextWithShadow(this.textRenderer, "Graphs (Transition Curve for ♦ movetheblock / ♦ smootheffect)", this.width / 2, boxTop + 6, 0xFFFFFFFF);
            drawButton(context, this.width - 36, boxTop + 4, 26, 14, "✔", 0xFF00C853, mouseX, mouseY);

            AnimationData.EasingGraph[] graphs = AnimationData.EasingGraph.values();
            int cardW = 54;
            int cardH = 44;
            int spacing = 8;
            int totalW = graphs.length * cardW + (graphs.length - 1) * spacing;
            int startX = (this.width - totalW) / 2;
            int cardY = boxTop + 22;

            for (int i = 0; i < graphs.length; i++) {
                AnimationData.EasingGraph g = graphs[i];
                int cx = startX + i * (cardW + spacing);
                boolean selected = (g == selectedGraph);
                context.fill(cx, cardY, cx + cardW, cardY + cardH, 0xFF2A2A32);
                context.drawBorder(cx, cardY, cardW, cardH, selected ? 0xFFFFFFFF : 0xFF454552);

                // Dashed guide lines like Image 3
                context.fill(cx + 4, cardY + 10, cx + cardW - 4, cardY + 11, 0x44FFFFFF);
                context.fill(cx + 4, cardY + cardH / 2, cx + cardW - 4, cardY + cardH / 2 + 1, 0x55FFFFFF);
                context.fill(cx + 4, cardY + cardH - 10, cx + cardW - 4, cardY + cardH - 9, 0x44FFFFFF);

                // Draw Mathematical Easing Curve Line
                int plotW = cardW - 12;
                int plotH = cardH - 12;
                for (int px = 0; px <= plotW; px++) {
                    double t = px / (double) plotW;
                    double eased = g.apply(t);
                    int drawX = cx + 6 + px;
                    int drawY = cardY + cardH - 6 - (int) Math.round(eased * plotH);
                    context.fill(drawX, drawY - 1, drawX + 2, drawY + 1, 0xFFFFFFFF);
                }

                context.drawCenteredTextWithShadow(this.textRenderer, g.label, cx + cardW / 2, cardY + cardH + 4, selected ? 0xFF00E5FF : 0xFFAAAAAA);
            }
        } else if (activePanel == SubPanel.EFFECTS) {
            int boxTop = transportY - 64;
            context.fill(0, boxTop, this.width, transportY, 0xF418181E);
            context.drawCenteredTextWithShadow(this.textRenderer, "EFFECTS: Pick Effect, then place ♦ ShowEffect (Start) & ♦ HideEffect (End)", this.width / 2, boxTop + 5, 0xFFFFD54F);

            AnimationData.EffectType[] types = AnimationData.EffectType.values();
            int bw = (this.width - 28) / types.length;
            for (int i = 0; i < types.length; i++) {
                int bx = 8 + i * (bw + 2);
                boolean sel = (types[i] == selectedEffect);
                drawButton(context, bx, boxTop + 18, bw, 18, types[i].label, sel ? 0xFF8E24AA : 0xFF2B2B36, mouseX, mouseY);
            }

            // Place ♦ ShowEffect and ♦ HideEffect buttons
            drawButton(context, this.width / 2 - 130, boxTop + 41, 124, 18, "+♦ ShowEffect (Start)", 0xFF00897B, mouseX, mouseY);
            drawButton(context, this.width / 2 + 6, boxTop + 41, 124, 18, "+♦ HideEffect (Stop)", 0xFFD81B60, mouseX, mouseY);

        } else if (activePanel == SubPanel.MOVE_EDIT) {
            int boxTop = transportY - 56;
            context.fill(0, boxTop, this.width, transportY, 0xF418181E);
            String info = String.format(Locale.US, "Move Offset from Origin:  X: %.1f   Y: %.1f   Z: %.1f   |   Curve: %s",
                    editDx, editDy, editDz, selectedGraph.label);
            context.drawCenteredTextWithShadow(this.textRenderer, info, this.width / 2, boxTop + 6, 0xFF80DEEA);

            String[] btns = {"X -1", "X +1", "Y -1", "Y +1", "Z -1", "Z +1", "+♦ Add MoveBlock"};
            int bw = (this.width - 30) / btns.length;
            for (int i = 0; i < btns.length; i++) {
                int bx = 8 + i * (bw + 2);
                int col = (i == btns.length - 1) ? 0xFF00ACC1 : 0xFF2C2C38;
                drawButton(context, bx, boxTop + 24, bw, 20, btns[i], col, mouseX, mouseY);
            }

        } else if (activePanel == SubPanel.SOUNDS) {
            // Searchable Sound Modal at Top of Screen
            int modalLeft = this.width / 2 - 140;
            int modalRight = this.width / 2 + 140;
            int modalTop = 24;
            int modalBottom = transportY - 6;

            context.fill(modalLeft, modalTop, modalRight, modalBottom, 0xF214141C);
            context.drawBorder(modalLeft, modalTop, modalRight - modalLeft, modalBottom - modalTop, 0xFF00ACC1);
            context.drawCenteredTextWithShadow(this.textRenderer, "SOUND LIST (" + filteredSoundIds.size() + " sounds)", this.width / 2, modalTop + 6, 0xFFFFFFFF);

            soundSearchField.setX(modalLeft + 10);
            soundSearchField.setY(modalTop + 20);
            soundSearchField.setWidth(modalRight - modalLeft - 20);
            soundSearchField.render(context, mouseX, mouseY, 0);

            int listTop = modalTop + 42;
            int rowH = 16;
            int maxRows = Math.max(1, (modalBottom - listTop - 6) / rowH);

            for (int i = 0; i < maxRows; i++) {
                int idx = soundScrollOffset + i;
                if (idx >= filteredSoundIds.size()) break;
                String soundId = filteredSoundIds.get(idx);
                int ry = listTop + i * rowH;

                context.fill(modalLeft + 8, ry, modalRight - 8, ry + rowH - 2, (i % 2 == 0) ? 0xFF20202A : 0xFF262632);
                String displayId = soundId.replace("minecraft:", "");
                if (displayId.length() > 28) displayId = displayId.substring(0, 28) + "..";
                context.drawTextWithShadow(this.textRenderer, displayId, modalLeft + 12, ry + 3, 0xFFE0E0E0);

                drawButton(context, modalRight - 78, ry + 1, 30, rowH - 4, "▶", 0xFF3949AB, mouseX, mouseY);
                drawButton(context, modalRight - 44, ry + 1, 32, rowH - 4, "+♦", 0xFF00897B, mouseX, mouseY);
            }
        }
    }

    private void drawButton(DrawContext context, int x, int y, int w, int h, String text, int bgColor, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        int fill = hovered ? brighten(bgColor) : bgColor;
        context.fill(x, y, x + w, y + h, fill);
        context.drawBorder(x, y, w, h, hovered ? 0xFFFFFFFF : 0xFF454555);
        context.drawCenteredTextWithShadow(this.textRenderer, text, x + w / 2, y + (h - 8) / 2, 0xFFFFFFFF);
    }

    private int brighten(int color) {
        int a = (color >> 24) & 0xFF;
        int r = Math.min(255, ((color >> 16) & 0xFF) + 28);
        int g = Math.min(255, ((color >> 8) & 0xFF) + 28);
        int b = Math.min(255, (color & 0xFF) + 28);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void drawDiamond(DrawContext context, int cx, int cy, int color) {
        context.fill(cx - 1, cy - 4, cx + 2, cy + 5, color);
        context.fill(cx - 2, cy - 3, cx + 3, cy + 4, color);
        context.fill(cx - 3, cy - 2, cx + 4, cy + 3, color);
        context.fill(cx - 4, cy - 1, cx + 5, cy + 2, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activePanel == SubPanel.SOUNDS && soundSearchField.mouseClicked(mouseX, mouseY, button)) {
            this.setFocused(soundSearchField);
            return true;
        }

        int panelTop = this.height - 142;
        int transportY = panelTop - 22;

        // 1. Check Active Sub-Panel Clicks
        if (handleSubPanelClick(mouseX, mouseY, transportY)) {
            return true;
        }

        // 2. Check Transport Bar Clicks (Image 2: [Reset], ▶, +♦, ↶, ↷)
        if (mouseY >= transportY && mouseY < panelTop) {
            if (inBounds(mouseX, mouseY, 8, transportY + 3, 56, 16)) {
                data.playing = false;
                data.currentTime = 0.0f;
                syncToServer(true); // Stop & restore original block
                return true;
            }
            if (inBounds(mouseX, mouseY, this.width / 2 - 16, transportY + 2, 32, 18)) {
                data.playing = !data.playing;
                syncToServer(false);
                return true;
            }
            if (inBounds(mouseX, mouseY, this.width - 116, transportY + 3, 34, 16)) {
                // "+♦" Quick Add Move Keyframe at playhead
                addMoveKeyframeAtPlayhead();
                return true;
            }
            if (inBounds(mouseX, mouseY, this.width - 76, transportY + 3, 30, 16)) {
                undo();
                return true;
            }
            if (inBounds(mouseX, mouseY, this.width - 40, transportY + 3, 30, 16)) {
                redo();
                return true;
            }
        }

        // 3. Check Timeline Scrubbing (Dragging White Playhead Line)
        if (mouseY >= panelTop + 4 && mouseY <= panelTop + 92 && mouseX >= getTimelineLeft() && mouseX <= getTimelineRight()) {
            draggingPlayhead = true;
            data.playing = false;
            data.currentTime = xToTime(mouseX);
            syncToServer(false);
            return true;
        }

        // 4. Check Bottom CapCut Toolbar Buttons
        int barY = this.height - 34;
        if (mouseY >= barY && mouseY <= barY + 24) {
            int btnW = (this.width - 24) / 7;
            for (int i = 0; i < 7; i++) {
                int bx = 8 + i * (btnW + 2);
                if (inBounds(mouseX, mouseY, bx, barY, btnW, 24)) {
                    switch (i) {
                        case 0 -> this.close();
                        case 1 -> activePanel = (activePanel == SubPanel.MOVE_EDIT) ? SubPanel.NONE : SubPanel.MOVE_EDIT;
                        case 2 -> toggleShowHideBlockAtPlayhead();
                        case 3 -> activePanel = (activePanel == SubPanel.GRAPHS) ? SubPanel.NONE : SubPanel.GRAPHS;
                        case 4 -> activePanel = (activePanel == SubPanel.EFFECTS) ? SubPanel.NONE : SubPanel.EFFECTS;
                        case 5 -> activePanel = (activePanel == SubPanel.SOUNDS) ? SubPanel.NONE : SubPanel.SOUNDS;
                        case 6 -> deleteNearestKeyframeAtPlayhead();
                    }
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handleSubPanelClick(double mouseX, double mouseY, int transportY) {
        if (activePanel == SubPanel.NONE) return false;

        if (activePanel == SubPanel.GRAPHS) {
            int boxTop = transportY - 82;
            if (inBounds(mouseX, mouseY, this.width - 36, boxTop + 4, 26, 14)) {
                activePanel = SubPanel.NONE;
                return true;
            }
            AnimationData.EasingGraph[] graphs = AnimationData.EasingGraph.values();
            int cardW = 54;
            int cardH = 44;
            int spacing = 8;
            int totalW = graphs.length * cardW + (graphs.length - 1) * spacing;
            int startX = (this.width - totalW) / 2;
            int cardY = boxTop + 22;

            for (int i = 0; i < graphs.length; i++) {
                int cx = startX + i * (cardW + spacing);
                if (inBounds(mouseX, mouseY, cx, cardY, cardW, cardH + 14)) {
                    pushUndoState();
                    selectedGraph = graphs[i];
                    // Apply selected graph curve to nearest MoveKeyframe at/after playhead
                    for (AnimationData.MoveKeyframe mk : data.moveKeyframes) {
                        if (mk.time >= data.currentTime - 0.05f) {
                            mk.graph = selectedGraph;
                            break;
                        }
                    }
                    if (!data.moveKeyframes.isEmpty()) {
                        data.moveKeyframes.getLast().graph = selectedGraph;
                    }
                    syncToServer(false);
                    return true;
                }
            }
        } else if (activePanel == SubPanel.EFFECTS) {
            int boxTop = transportY - 64;
            AnimationData.EffectType[] types = AnimationData.EffectType.values();
            int bw = (this.width - 28) / types.length;
            for (int i = 0; i < types.length; i++) {
                int bx = 8 + i * (bw + 2);
                if (inBounds(mouseX, mouseY, bx, boxTop + 18, bw, 18)) {
                    selectedEffect = types[i];
                    return true;
                }
            }
            // +♦ ShowEffect (Start)
            if (inBounds(mouseX, mouseY, this.width / 2 - 130, boxTop + 41, 124, 18)) {
                pushUndoState();
                data.effectKeyframes.add(new AnimationData.EffectKeyframe(data.currentTime, selectedEffect, true, selectedGraph));
                syncToServer(false);
                return true;
            }
            // +♦ HideEffect (Stop)
            if (inBounds(mouseX, mouseY, this.width / 2 + 6, boxTop + 41, 124, 18)) {
                pushUndoState();
                data.effectKeyframes.add(new AnimationData.EffectKeyframe(data.currentTime, selectedEffect, false, selectedGraph));
                syncToServer(false);
                return true;
            }
        } else if (activePanel == SubPanel.MOVE_EDIT) {
            int boxTop = transportY - 56;
            int bw = (this.width - 30) / 7;
            for (int i = 0; i < 7; i++) {
                int bx = 8 + i * (bw + 2);
                if (inBounds(mouseX, mouseY, bx, boxTop + 24, bw, 20)) {
                    switch (i) {
                        case 0 -> editDx -= 1.0;
                        case 1 -> editDx += 1.0;
                        case 2 -> editDy -= 1.0;
                        case 3 -> editDy += 1.0;
                        case 4 -> editDz -= 1.0;
                        case 5 -> editDz += 1.0;
                        case 6 -> addMoveKeyframeAtPlayhead();
                    }
                    return true;
                }
            }
        } else if (activePanel == SubPanel.SOUNDS) {
            int modalLeft = this.width / 2 - 140;
            int modalRight = this.width / 2 + 140;
            int modalTop = 24;
            int modalBottom = transportY - 6;
            int listTop = modalTop + 42;
            int rowH = 16;
            int maxRows = Math.max(1, (modalBottom - listTop - 6) / rowH);

            for (int i = 0; i < maxRows; i++) {
                int idx = soundScrollOffset + i;
                if (idx >= filteredSoundIds.size()) break;
                String soundId = filteredSoundIds.get(idx);
                int ry = listTop + i * rowH;

                // Preview Sound button ▶
                if (inBounds(mouseX, mouseY, modalRight - 78, ry + 1, 30, rowH - 4)) {
                    Identifier id = Identifier.tryParse(soundId);
                    if (id != null && MinecraftClient.getInstance().player != null) {
                        SoundEvent evt = Registries.SOUND_EVENT.get(id);
                        if (evt != null) {
                            MinecraftClient.getInstance().player.playSound(evt, 1.0f, 1.0f);
                        }
                    }
                    return true;
                }
                // Add Sound Keyframe button +♦
                if (inBounds(mouseX, mouseY, modalRight - 44, ry + 1, 32, rowH - 4)) {
                    pushUndoState();
                    data.soundKeyframes.add(new AnimationData.SoundKeyframe(data.currentTime, soundId));
                    syncToServer(false);
                    return true;
                }
            }
        }
        return false;
    }

    private void addMoveKeyframeAtPlayhead() {
        pushUndoState();
        data.moveKeyframes.removeIf(k -> Math.abs(k.time - data.currentTime) < 0.08f);
        data.moveKeyframes.add(new AnimationData.MoveKeyframe(data.currentTime, editDx, editDy, editDz, selectedGraph));
        syncToServer(false);
    }

    private void toggleShowHideBlockAtPlayhead() {
        pushUndoState();
        boolean currentlyShown = data.evaluateVisibility(data.currentTime);
        data.visibilityKeyframes.removeIf(k -> Math.abs(k.time - data.currentTime) < 0.08f);
        data.visibilityKeyframes.add(new AnimationData.VisibilityKeyframe(data.currentTime, !currentlyShown));
        syncToServer(false);
    }

    private void deleteNearestKeyframeAtPlayhead() {
        pushUndoState();
        float t = data.currentTime;
        data.moveKeyframes.removeIf(k -> k.time > 0.01f && Math.abs(k.time - t) < 0.35f);
        data.visibilityKeyframes.removeIf(k -> k.time > 0.01f && Math.abs(k.time - t) < 0.35f);
        data.effectKeyframes.removeIf(k -> Math.abs(k.time - t) < 0.35f);
        data.soundKeyframes.removeIf(k -> Math.abs(k.time - t) < 0.35f);
        syncToServer(false);
    }

    private boolean inBounds(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (draggingPlayhead) {
            data.currentTime = xToTime(mouseX);
            syncToServer(false);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingPlayhead = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (activePanel == SubPanel.SOUNDS) {
            int delta = verticalAmount > 0 ? -3 : 3;
            soundScrollOffset = Math.max(0, Math.min( Math.max(0, filteredSoundIds.size() - 5), soundScrollOffset + delta));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (activePanel == SubPanel.SOUNDS && soundSearchField.isFocused()) {
            if (soundSearchField.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (activePanel == SubPanel.SOUNDS && soundSearchField.isFocused()) {
            if (soundSearchField.charTyped(chr, modifiers)) {
                return true;
            }
        }
        return super.charTyped(chr, modifiers);
    }
    }
