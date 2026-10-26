package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.Organizer;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.init.ModEntities;
import com.ale.warbandcraft.worker.OrganizerEntity;
import com.ale.warbandcraft.worker.OrganizerGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** El Organizador: reparte lo del Campamento entre los cofres vinculados, respeta los marcos y avisa si faltan cofres. */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class OrganizerGameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CAMP_POS = new BlockPos(1, 2, 1);

    private static CampBlockEntity camp(GameTestHelper h, BlockPos... chests) {
        h.setBlock(CAMP_POS, ModBlocks.CAMP.get());
        CampBlockEntity camp = (CampBlockEntity) h.getBlockEntity(CAMP_POS);
        camp.setOwner(UUID.nameUUIDFromBytes(("warbandcraft-org@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes()));
        for (BlockPos c : chests) {
            h.setBlock(c, Blocks.BARREL);   // barriles: no se juntan en cofres dobles
            check(camp.toggleLink(h.absolutePos(c)) == CampBlockEntity.LinkResult.LINKED, "no se vinculó " + c);
        }
        return camp;
    }

    private static OrganizerEntity organizer(GameTestHelper h, CampBlockEntity camp) {
        OrganizerEntity o = ModEntities.ORGANIZER.get().create(h.getLevel());
        check(o != null, "no se creó el Organizador");
        o.setCamp(camp.getBlockPos());
        return o;
    }

    private static int count(Container c, Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
    }

    /** Pan, hierro, piedra y semillas con 3 cofres: cada cosa queda junta en un cofre, lo de nombre se queda, el Campamento vacío. */
    @GameTest(template = TEMPLATE)
    public static void organizerSpreadsTheInbox(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos[] chests = {new BlockPos(3, 2, 1), new BlockPos(5, 2, 1), new BlockPos(7, 2, 1)};
        CampBlockEntity camp = camp(h, chests);
        camp.insert(new ItemStack(Items.BREAD, 100));
        camp.insert(new ItemStack(Items.IRON_INGOT, 20));
        camp.insert(new ItemStack(Items.COBBLESTONE, 64));
        camp.insert(new ItemStack(Items.WHEAT_SEEDS, 10));
        ItemStack named = new ItemStack(Items.IRON_SWORD);
        named.setHoverName(Component.literal("La de papá"));
        camp.setItem(53, named);
        OrganizerEntity o = organizer(h, camp);
        int trips = OrganizerGoal.runNow(level, o, 50);
        check(trips > 0 && trips < 50, "viajes raros: " + trips);

        for (int i = 0; i < camp.getContainerSize(); i++) {
            ItemStack s = camp.getItem(i);
            check(s.isEmpty() || s.hasCustomHoverName(), "quedó en el Campamento: " + s);
        }
        check(camp.getItem(53).hasCustomHoverName(), "la espada con nombre no se tenía que mover");
        for (Item item : List.of(Items.BREAD, Items.IRON_INGOT, Items.COBBLESTONE, Items.WHEAT_SEEDS)) {
            int where = 0;
            for (BlockPos c : chests) if (count((Container) h.getBlockEntity(c), item) > 0) where++;
            check(where == 1, item + " quedó repartido en " + where + " cofres");
        }
        check(o.getMissing().isEmpty(), "con lugar no tendría que avisar que faltan cofres: " + o.getMissing());
        check(OrganizerGoal.runNow(level, o, 50) == 0, "ordenado, no tendría que mover nada más");
        // Un cofre con hierro y piedra mezclados en uno de comida: los saca a su lugar
        Container food = null;
        for (BlockPos c : chests) if (count((Container) h.getBlockEntity(c), Items.BREAD) > 0) food = (Container) h.getBlockEntity(c);
        check(food != null, "no encuentro el cofre del pan");
        food.setItem(26, new ItemStack(Items.IRON_INGOT, 3));
        OrganizerGoal.runNow(level, o, 50);
        check(count(food, Items.IRON_INGOT) == 0, "no sacó el hierro del cofre de la comida");
        h.succeed();
    }

    /** Un marco con un lingote en el cofre más lejano: el hierro va ahí. */
    @GameTest(template = TEMPLATE)
    public static void organizerFollowsFrames(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos near = new BlockPos(3, 2, 1), far = new BlockPos(7, 2, 5);
        CampBlockEntity camp = camp(h, near, far);
        camp.insert(new ItemStack(Items.IRON_INGOT, 40));
        ItemFrame frame = new ItemFrame(level, h.absolutePos(far.north()), Direction.NORTH);
        frame.setItem(new ItemStack(Items.IRON_INGOT));
        level.addFreshEntity(frame);
        OrganizerEntity o = organizer(h, camp);
        OrganizerGoal.runNow(level, o, 50);
        frame.discard();
        check(count((Container) h.getBlockEntity(far), Items.IRON_INGOT) == 40, "el hierro no fue al cofre del marco");
        check(count(camp, Items.IRON_INGOT) == 0, "quedó hierro en el Campamento");
        h.succeed();
    }

    /** Un solo cofre y demasiada piedra: lo que no entra queda en el Campamento y avisa "faltan cofres". */
    @GameTest(template = TEMPLATE)
    public static void organizerWarnsWhenFull(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos only = new BlockPos(3, 2, 1);
        CampBlockEntity camp = camp(h, only);
        for (int i = 0; i < 30; i++) camp.insert(new ItemStack(Items.COBBLESTONE, 64));
        camp.insert(new ItemStack(Items.BREAD, 10));
        OrganizerEntity o = organizer(h, camp);
        OrganizerGoal.runNow(level, o, 50);
        check(count((Container) h.getBlockEntity(only), Items.COBBLESTONE) > 0, "no llevó nada al único cofre");
        check(count(camp, Items.COBBLESTONE) > 0, "la piedra que no entra tendría que seguir en el Campamento");
        check(o.getMissing().contains(Organizer.Category.BUILDING), "tendría que avisar que faltan cofres para construcción: " + o.getMissing());
        h.succeed();
    }

    private static void check(boolean ok, String msg) {
        if (!ok) throw new GameTestAssertException(msg);
    }
}
