package com.ale.warbandcraft.client.camp;

import com.ale.warbandcraft.camp.CampTabs;
import com.ale.warbandcraft.faction.Doctrine;
import com.ale.warbandcraft.network.ChooseDoctrineMessage;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.OpenDoctrineMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/** Pestaña Doctrinas del Campamento: las 4 doctrinas, cuál tenés, cuánto cuesta cambiar y tu identidad de Recruits. */
@OnlyIn(Dist.CLIENT)
public class DoctrineScreen extends Screen {
    private static final int W = 300, H = 214, ROW = 36;

    private final OpenDoctrineMessage state;
    private int left, top;

    public DoctrineScreen(OpenDoctrineMessage state) {
        super(Component.translatable("gui.warbandcraft.tab.doctrines"));
        this.state = state;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        for (Button b : CampTabButtons.create(state.camp, CampTabs.DOCTRINES, left, top)) addRenderableWidget(b);

        boolean canChange = state.free || state.waitTicks <= 0;
        for (Doctrine d : Doctrine.values()) {
            int y = top + 38 + d.ordinal() * ROW;
            boolean current = d.ordinal() == state.current;
            Button b = Button.builder(Component.translatable(current ? "gui.warbandcraft.doctrine.current" : "gui.warbandcraft.doctrine.adopt"),
                            x -> ModNetworking.CHANNEL.sendToServer(new ChooseDoctrineMessage(state.camp, d.ordinal())))
                    .bounds(left + W - 80, y + 4, 72, 20).build();
            b.active = !current && canChange;
            addRenderableWidget(b);
        }
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx);
        gfx.fill(left - 1, top - 1, left + W + 1, top + H + 1, 0xFF6B5A3A);
        gfx.fill(left, top, left + W, top + H, 0xF0201810);
        gfx.drawString(font, Component.translatable("gui.warbandcraft.doctrine.title").withStyle(ChatFormatting.GOLD), left + 8, top + 7, 0xFFFFFF);

        Component cost;
        if (state.free) cost = Component.translatable("gui.warbandcraft.doctrine.free");
        else if (state.waitTicks > 0) cost = Component.translatable("gui.warbandcraft.doctrine.wait", state.waitTicks / 1200 + 1);
        else cost = Component.translatable("gui.warbandcraft.doctrine.cost", Doctrine.CHANGE_COST_EMERALDS);
        gfx.drawString(font, cost, left + 8, top + 21, 0xBBBBBB);

        for (Doctrine d : Doctrine.values()) {
            int y = top + 38 + d.ordinal() * ROW;
            boolean current = d.ordinal() == state.current;
            if (current) gfx.fill(left + 4, y, left + W - 4, y + ROW - 4, 0x40D4A017);
            gfx.drawString(font, d.displayName().copy().withStyle(current ? ChatFormatting.GOLD : ChatFormatting.WHITE), left + 8, y + 3, 0xFFFFFF);
            List<FormattedCharSequence> lines = font.split(d.description(), W - 100);
            for (int i = 0; i < Math.min(2, lines.size()); i++) gfx.drawString(font, lines.get(i), left + 8, y + 14 + i * 9, 0xAAAAAA);
        }

        int iy = top + H - 26;
        gfx.fill(left + 4, iy - 4, left + W - 4, iy - 3, 0xFF6B5A3A);
        if (state.factionName.isEmpty()) {
            List<FormattedCharSequence> lines = font.split(Component.translatable("gui.warbandcraft.identity.none"), W - 16);
            for (int i = 0; i < Math.min(2, lines.size()); i++) gfx.drawString(font, lines.get(i), left + 8, iy + i * 10, 0xAAAAAA);
        } else {
            if (!state.banner.isEmpty()) gfx.renderItem(state.banner, left + 8, iy + 1);
            gfx.fill(left + 28, iy + 4, left + 38, iy + 14, 0xFF000000 | state.armorColor);
            gfx.drawString(font, Component.translatable("gui.warbandcraft.identity", state.factionName), left + 44, iy + 2, 0xFFFFFF);
            gfx.drawString(font, Component.translatable("gui.warbandcraft.identity.edit"), left + 44, iy + 12, 0x888888);
        }
        super.render(gfx, mouseX, mouseY, partialTick);
    }
}
