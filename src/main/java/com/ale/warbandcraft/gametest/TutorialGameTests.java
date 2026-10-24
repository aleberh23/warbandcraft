package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.tutorial.Tutorial;
import com.ale.warbandcraft.tutorial.TutorialBooks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Salón del tutorial: se arma lejos (a 7000) con sus 25 atriles con libro y carteles, y se puede aparecer adentro. */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class TutorialGameTests {
    @GameTest(template = "empty", batch = "tutorial", timeoutTicks = 200)
    public static void tutorialHall(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<TutorialBooks.Topic> topics = TutorialBooks.load(level.getServer());
        check(topics.size() == 25, "hay " + topics.size() + " libros, se esperaban 25");
        for (TutorialBooks.Topic t : topics) {
            check(!t.pages().isEmpty() && t.pages().size() <= 100, t.id() + ": " + t.pages().size() + " páginas");
            check(!t.title().isBlank() && !t.title().equals(t.id()), t.id() + ": sin título");
            check(t.sign().size() <= 3, t.id() + ": el cartel tiene más de 3 renglones");
            for (var p : t.pages()) {
                int lines = p.getString().split("\n", -1).length;
                check(lines <= 14, t.id() + ": una página con " + lines + " renglones");
            }
        }

        BlockPos spawn = Tutorial.build(level, new BlockPos(7000, 80, 7000), true);
        check(level.getBlockState(spawn).isAir() && level.getBlockState(spawn.above()).isAir(), "el lugar de aparecer no está libre");
        check(level.getBlockState(spawn.below()).isSolidRender(level, spawn.below()), "no hay piso donde se aparece");

        int lecterns = 0, signs = 0;
        for (BlockPos p : BlockPos.betweenClosed(6980, spawn.getY() - 1, 6980, 7020, spawn.getY() + 8, 7020)) {
            if (level.getBlockEntity(p) instanceof LecternBlockEntity l) {
                lecterns++;
                ItemStack book = l.getBook();
                check(book.is(Items.WRITTEN_BOOK), "atril sin libro en " + p);
                ListTag pages = book.getOrCreateTag().getList("pages", Tag.TAG_STRING);
                check(!pages.isEmpty(), "libro sin páginas en " + p);
            }
            if (level.getBlockEntity(p) instanceof SignBlockEntity s && s.isWaxed()) signs++;
        }
        check(lecterns == 25, "hay " + lecterns + " atriles, se esperaban 25");
        check(signs >= 26, "hay " + signs + " carteles encerados");
        h.succeed();
    }

    private static void check(boolean ok, String msg) {
        if (!ok) throw new GameTestAssertException(msg);
    }
}
