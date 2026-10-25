package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.kit.ClassAssigner;
import com.ale.warbandcraft.network.ToBarracksMessage;
import com.mojang.authlib.GameProfile;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pruebas del Hito 8 (barracones): nombre del Campamento, guardar y sacar con la ficha completa, caballo,
 * capacidad, comida y deserción, guardado automático, vuelta de los guardias y Campamento roto.
 * El guardado automático no corre solo en el servidor de pruebas: acá se llama a mano.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H8GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CAMP_POS = new BlockPos(1, 1, 1);
    private static final BlockPos CHEST_POS = new BlockPos(3, 1, 1);
    private static final BlockPos SOLDIER_POS = new BlockPos(4, 1, 4);
    // y=2: en la estructura "empty" el piso de piedra queda en y=1 relativo (el puesto tiene que estar libre)
    private static final BlockPos POST_POS = new BlockPos(6, 2, 5);

    // ------------------------------------------------------------------ nombre

    /** El nombre del yunque pasa al Campamento al ponerlo, el registro lo conoce y el bloque roto lo conserva. */
    @GameTest(template = TEMPLATE)
    public static void campNameFromAnvilAndKeptOnBreak(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(owner(h), "ale-h8"));
        h.setBlock(CAMP_POS, ModBlocks.CAMP.get());
        BlockPos abs = h.absolutePos(CAMP_POS);
        BlockState state = level.getBlockState(abs);
        ItemStack named = new ItemStack(ModBlocks.CAMP.get());
        named.setHoverName(Component.literal("Fuerte del Río"));
        state.getBlock().setPlacedBy(level, abs, state, player, named);

        CampBlockEntity camp = (CampBlockEntity) level.getBlockEntity(abs);
        check(camp != null && "Fuerte del Río".equals(camp.nameString()), "el Campamento no tomó el nombre del yunque");
        check("Fuerte del Río".equals(CampRegistry.get(level).nameOf(abs)), "el registro no conoce el nombre");

        List<ItemStack> drops = Block.getDrops(state, level, abs, camp);
        check(drops.stream().anyMatch(s -> s.getHoverName().getString().equals("Fuerte del Río")), "el bloque roto no conserva el nombre");

        check(camp.rename("Puesto Norte"), "renombrar falló");
        check("Puesto Norte".equals(CampRegistry.get(level).nameOf(abs)), "el registro no se enteró del nombre nuevo");

        // Sin nombre: "Campamento N"
        BlockPos second = CAMP_POS.east(4);
        h.setBlock(second, ModBlocks.CAMP.get());
        BlockPos abs2 = h.absolutePos(second);
        state.getBlock().setPlacedBy(level, abs2, level.getBlockState(abs2), player, new ItemStack(ModBlocks.CAMP.get()));
        String name2 = ((CampBlockEntity) level.getBlockEntity(abs2)).nameString();
        check(name2.matches(".*\\d+.*"), "el Campamento sin nombre debería ser numerado, es '" + name2 + "'");
        h.succeed();
    }

    // ------------------------------------------------------------------ guardar y sacar

    /** El soldado vuelve con nivel, nombre, equipo, mochila, clase y versión de kit, siguiéndote. */
    @GameTest(template = TEMPLATE)
    public static void storeAndDeployKeepsSheet(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        AbstractRecruitEntity r = spawnRecruit(h, SOLDIER_POS);
        r.setXpLevel(4);
        r.setCustomName(Component.literal("Gonzalo"));
        r.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        ClassAssigner.putInBackpack(r.getInventory(), new ItemStack(Items.BREAD, 7));
        CompoundTag pd = r.getPersistentData();
        pd.putString(ClassAssigner.TAG_CLASS, "warbandcraft:infante");
        pd.putInt(ClassAssigner.TAG_TIER, 1);
        pd.putInt(ClassAssigner.TAG_KIT_VERSION, 3);

        check(Barracks.store(h.getLevel(), r, camp.getBlockPos(), false) == Barracks.StoreResult.OK, "no se guardó");
        check(r.isRemoved(), "el soldado guardado sigue en el mundo");
        check(BarracksData.get(h.getLevel()).count(camp.getBlockPos()) == 1, "el barracón no tiene 1 soldado");

        check(Barracks.deploy(h.getLevel(), camp.getBlockPos(), null, 1) == 1, "no salió");
        AbstractRecruitEntity out = only(h);
        check(out.getXpLevel() == 4, "perdió el nivel");
        check("Gonzalo".equals(out.getName().getString()), "perdió el nombre");
        check(out.getMainHandItem().is(Items.IRON_SWORD), "perdió la espada");
        check(countIn(out.getInventory(), Items.BREAD) == 7, "perdió la mochila");
        CompoundTag d = out.getPersistentData();
        check("warbandcraft:infante".equals(d.getString(ClassAssigner.TAG_CLASS)) && d.getInt(ClassAssigner.TAG_TIER) == 1
                && d.getInt(ClassAssigner.TAG_KIT_VERSION) == 3, "perdió la clase, el nivel de kit o la versión");
        check(d.getLong(Barracks.TAG_CAMP) == camp.getBlockPos().asLong(), "no quedó atado al Campamento");
        check(out.getFollowState() == 1, "debería salir siguiéndote, está en " + out.getFollowState());
        check(BarracksData.get(h.getLevel()).count(camp.getBlockPos()) == 0, "el barracón no quedó vacío");
        h.succeed();
    }

    /** Un caballero montado se guarda con su caballo y vuelve montado. */
    @GameTest(template = TEMPLATE)
    public static void mountedKnightKeepsHorse(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        AbstractRecruitEntity r = spawnRecruit(h, SOLDIER_POS);
        AbstractHorse horse = (AbstractHorse) h.spawn(EntityType.HORSE, SOLDIER_POS);
        horse.getPersistentData().putUUID(ClassAssigner.TAG_HORSE_OWNER, owner(h));
        check(r.startRiding(horse, true), "no se pudo montar");

        check(Barracks.store(h.getLevel(), r, camp.getBlockPos(), false) == Barracks.StoreResult.OK, "no se guardó");
        check(horse.isRemoved(), "el caballo quedó en el mundo");
        Barracks.deploy(h.getLevel(), camp.getBlockPos(), null, 1);
        AbstractRecruitEntity out = only(h);
        check(out.getVehicle() instanceof AbstractHorse back
                        && owner(h).equals(back.getPersistentData().getUUID(ClassAssigner.TAG_HORSE_OWNER)),
                "volvió sin su caballo");
        h.succeed();
    }

    /** Con el barracón lleno, el soldado se queda en el mundo. */
    @GameTest(template = TEMPLATE)
    public static void fullBarracksRejects(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        BarracksData.Barracks b = BarracksData.get(h.getLevel()).getOrCreate(camp.getBlockPos(), owner(h));
        for (int i = 0; i < BarracksData.CAPACITY; i++) b.soldiers.add(fakeSheet(i));
        AbstractRecruitEntity r = spawnRecruit(h, SOLDIER_POS);
        check(Barracks.store(h.getLevel(), r, camp.getBlockPos(), false) == Barracks.StoreResult.FULL, "el 41º no debería entrar");
        check(r.isAlive() && !r.isRemoved(), "el soldado que no entró desapareció");
        BarracksData.get(h.getLevel()).remove(camp.getBlockPos());
        h.succeed();
    }

    /** Un soldado peleando no se guarda, y "guardar a los de cerca" no toca soldados de otro jugador. */
    @GameTest(template = TEMPLATE)
    public static void fightingOrForeignNotStored(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        AbstractRecruitEntity fighter = spawnRecruit(h, SOLDIER_POS);
        Zombie zombie = h.spawn(EntityType.ZOMBIE, SOLDIER_POS.east(3));
        fighter.setTarget(zombie);
        check(Barracks.store(h.getLevel(), fighter, camp.getBlockPos(), false) == Barracks.StoreResult.FIGHTING,
                "un soldado peleando no debería guardarse");
        fighter.setTarget(null);
        zombie.discard();

        AbstractRecruitEntity foreign = spawnRecruit(h, SOLDIER_POS.south());
        foreign.setOwnerUUID(Optional.of(UUID.randomUUID()));
        int stored = Barracks.storeNear(h.getLevel(), camp.getBlockPos(), owner(h));
        check(stored == 1, "debería guardarse solo el propio (ya sin pelear), se guardaron " + stored);
        check(foreign.isAlive() && !foreign.isRemoved(), "se guardó el soldado de otro jugador");
        h.succeed();
    }

    /** Un puesto tapado: el guardia sale en el lugar libre más cercano. */
    @GameTest(template = TEMPLATE)
    public static void blockedPostUsesNearestFreeSpot(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        AbstractRecruitEntity g = guardAt(h, POST_POS);
        Vec3 post = g.getHoldPos();
        Barracks.store(h.getLevel(), g, camp.getBlockPos(), false);
        h.setBlock(POST_POS, Blocks.STONE);
        h.setBlock(POST_POS.above(), Blocks.STONE);

        check(Barracks.deployGuards(h.getLevel(), camp.getBlockPos()) == 1, "el guardia no salió");
        AbstractRecruitEntity out = only(h);
        check(!out.blockPosition().equals(h.absolutePos(POST_POS)), "salió adentro de la piedra");
        check(out.position().distanceTo(post) <= 5.5, "salió lejos de su puesto: " + out.position().distanceTo(post));
        check(post.equals(out.getHoldPos()), "perdió su puesto");
        h.succeed();
    }

    // ------------------------------------------------------------------ comida y deserción

    /** Un día: 2 soldados comen 8 puntos; sale de un cofre vinculado (2 panes de 5, sobran 2 puntos). */
    @GameTest(template = TEMPLATE)
    public static void oneDayEatsFromLinkedChest(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        h.setBlock(CHEST_POS, Blocks.CHEST);
        Container chest = (Container) h.getBlockEntity(CHEST_POS);
        camp.toggleLink(h.absolutePos(CHEST_POS));
        chest.setItem(0, new ItemStack(Items.BREAD, 10));
        chest.setItem(1, new ItemStack(Items.ROTTEN_FLESH, 10));
        storeTwo(h, camp);
        BarracksData.Barracks b = BarracksData.get(h.getLevel()).at(camp.getBlockPos());
        long day = b.lastFedDay;

        check(Barracks.feed(h.getLevel(), camp.getBlockPos(), camp, day + 1) == 0, "nadie debería irse");
        check(countIn(chest, Items.BREAD) == 8, "deberían quedar 8 panes, quedan " + countIn(chest, Items.BREAD));
        check(countIn(chest, Items.ROTTEN_FLESH) == 10, "comieron carne podrida");
        check(b.foodCredit == 2 && b.hungryDays == 0, "sobra/hambre mal: " + b.foodCredit + "/" + b.hungryDays);
        h.succeed();
    }

    /** Sin comida: el 1er día nadie se va; el 2º se va el 10% (mínimo 1), el de menor nivel. */
    @GameTest(template = TEMPLATE)
    public static void hungerMakesLowestLevelDesert(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        int[] levels = {5, 1, 3};
        for (int i = 0; i < levels.length; i++) {
            AbstractRecruitEntity r = spawnRecruit(h, SOLDIER_POS.east(i));
            r.setXpLevel(levels[i]);
            Barracks.store(h.getLevel(), r, camp.getBlockPos(), false);
        }
        BarracksData.Barracks b = BarracksData.get(h.getLevel()).at(camp.getBlockPos());
        long day = b.lastFedDay;

        check(Barracks.feed(h.getLevel(), camp.getBlockPos(), camp, day + 1) == 0, "el 1er día sin comer nadie se va");
        check(b.hungryDays == 1, "debería llevar 1 día sin comer");
        check(Barracks.feed(h.getLevel(), camp.getBlockPos(), camp, day + 2) == 1, "el 2º día se tiene que ir 1");
        check(b.soldiers.size() == 2 && b.soldiers.stream().noneMatch(s -> s.xpLevel == 1), "se fue uno que no era el de menor nivel");
        h.succeed();
    }

    /** Campamento descargado 3 días: al volver se cobran los 3 días juntos. */
    @GameTest(template = TEMPLATE)
    public static void missedDaysAreCharged(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        camp.setItem(0, new ItemStack(Items.BREAD, 30));
        storeTwo(h, camp);
        BarracksData.Barracks b = BarracksData.get(h.getLevel()).at(camp.getBlockPos());
        long day = b.lastFedDay;

        Barracks.feed(h.getLevel(), camp.getBlockPos(), camp, day + 3);
        // 3 días × 8 puntos = 24: 5 panes (25 puntos)
        check(camp.count(Items.BREAD) == 25, "deberían quedar 25 panes, quedan " + camp.count(Items.BREAD));
        check(b.lastFedDay == day + 3, "no quedó al día");
        check(Barracks.foodDays(camp, b) == (25 * 5 + b.foodCredit) / 8, "foodDays mal calculado");
        h.succeed();
    }

    // ------------------------------------------------------------------ automático y guardias

    /** Dueño lejos: se guardan el guardia, el que camina y el suelto; el que te sigue, no. Al volver, solo el guardia sale. */
    @GameTest(template = TEMPLATE)
    public static void autoStoreAndGuardsReturn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CampBlockEntity camp = placeCamp(h);
        AbstractRecruitEntity follower = spawnRecruit(h, SOLDIER_POS);
        follower.setFollowState(1);
        AbstractRecruitEntity guard = guardAt(h, POST_POS);
        Vec3 post = guard.getHoldPos();
        float yRot = 90.0F;
        guard.setYRot(yRot);
        AbstractRecruitEntity walker = spawnRecruit(h, SOLDIER_POS.east(2));
        walker.setHoldPos(Vec3.atBottomCenterOf(h.absolutePos(SOLDIER_POS.east(8))));
        walker.setFollowState(3);
        AbstractRecruitEntity wanderer = spawnRecruit(h, SOLDIER_POS.south(2));
        wanderer.setFollowState(0);

        // Dueño cerca: nadie se guarda
        Vec3 near = Vec3.atCenterOf(h.absolutePos(SOLDIER_POS));
        for (AbstractRecruitEntity r : List.of(follower, guard, walker, wanderer)) {
            check(Barracks.autoStore(level, r, near) == null, "con el dueño cerca no se guarda nadie");
        }
        // Dueño desconectado
        for (AbstractRecruitEntity r : List.of(follower, guard, walker, wanderer)) Barracks.autoStore(level, r, null);
        check(follower.isAlive() && !follower.isRemoved(), "el que te sigue no se guarda");
        check(guard.isRemoved() && walker.isRemoved() && wanderer.isRemoved(), "no se guardaron los que quedaron atrás");
        BarracksData.Barracks b = BarracksData.get(level).at(camp.getBlockPos());
        check(b.soldiers.size() == 3 && b.guards() == 1, "deberían ser 3 guardados y 1 guardia, son " + b.soldiers.size() + "/" + b.guards());

        // Vuelve el dueño
        check(Barracks.returnGuards(level, owner(h), near) == 1, "debería volver solo el guardia");
        AbstractRecruitEntity back = level.getEntitiesOfClass(AbstractRecruitEntity.class, area(h),
                r -> r.isAlive() && r != follower && Objects.equals(r.getOwnerUUID(), owner(h))).get(0);
        check(back.position().distanceTo(post) < 0.01, "el guardia no volvió a su puesto exacto: " + back.position() + " vs " + post + " hold " + back.getHoldPos());
        check(back.getFollowState() == 3 && post.equals(back.getHoldPos()), "el guardia no conservó su orden y puesto");
        check(Math.abs(back.getYRot() - yRot) < 0.01, "el guardia no mira para el mismo lado");
        check(b.soldiers.size() == 2, "los demás tienen que quedar adentro");
        h.succeed();
    }

    /** Un guardia lejos del Campamento vuelve al pasar cerca de su puesto, no del Campamento. */
    @GameTest(template = TEMPLATE)
    public static void farGuardReturnsNearPost(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos farCamp = h.absolutePos(new BlockPos(200, 1, 0));
        CampRegistry.get(level).register(owner(h), farCamp);
        AbstractRecruitEntity g = guardAt(h, POST_POS);
        Vec3 post = g.getHoldPos();
        Barracks.store(level, g, farCamp, false);

        check(Barracks.returnGuards(level, owner(h), Vec3.atCenterOf(farCamp)) == 0, "volvió estando el dueño en el Campamento lejos");
        check(Barracks.returnGuards(level, owner(h), post.add(10, 0, 0)) == 1, "no volvió con el dueño cerca del puesto");
        AbstractRecruitEntity out = only(h);
        check(out.position().distanceTo(post) < 0.01, "no salió en su puesto: " + out.position() + " vs " + post);
        CampRegistry.get(level).unregister(owner(h), farCamp);
        BarracksData.get(level).remove(farCamp);
        h.succeed();
    }

    /** Orden "Al barracón": camina, y al llegar se guarda (aunque quede quieto ahí, no cuenta como guardia). */
    @GameTest(template = TEMPLATE)
    public static void sendHomeStoresOnArrival(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        AbstractRecruitEntity r = spawnRecruit(h, CAMP_POS.east());
        check(Barracks.sendHome(h.getLevel(), r, null), "no tiene Campamento al que ir");
        Barracks.checkArrivals(h.getLevel());
        check(r.isRemoved(), "llegó y no se guardó");
        BarracksData.Barracks b = BarracksData.get(h.getLevel()).at(camp.getBlockPos());
        check(b.soldiers.size() == 1 && b.guards() == 0, "se guardó como guardia");
        h.succeed();
    }

    /** "Al barracón" trabado (pedido de Ale): si no se acerca en {@link Barracks#STUCK_SECONDS}, se guarda igual; otra orden lo corta. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void sendHomeStuckStoresAnyway(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CampBlockEntity camp = placeCamp(h);
        AbstractRecruitEntity stuck = spawnRecruit(h, CAMP_POS.east(6));
        AbstractRecruitEntity changed = spawnRecruit(h, CAMP_POS.west(6));
        stuck.setNoAi(true);
        changed.setNoAi(true);
        check(Barracks.sendHome(level, stuck, null) && Barracks.sendHome(level, changed, null), "no tiene Campamento al que ir");
        Barracks.checkArrivals(level);
        check(!stuck.isRemoved(), "se guardó antes de tiempo");
        changed.setFollowState(Barracks.FOLLOW_OWNER);   // otra orden en el camino
        h.runAfterDelay(Barracks.STUCK_SECONDS * 20L + 40, () -> {
            Barracks.checkArrivals(level);
            check(stuck.isRemoved(), "se trabó y no se guardó");
            check(!changed.isRemoved() && !changed.getPersistentData().getBoolean(Barracks.TAG_TO_BARRACKS),
                    "con otra orden igual siguió yendo al barracón");
            check(BarracksData.get(level).count(camp.getBlockPos()) == 1, "no quedó en el barracón");
            h.succeed();
        });
    }

    /** Vista aérea: mandarlos a OTRO Campamento tuyo los ata a ese y se guardan ahí; a uno ajeno, no. */
    @GameTest(template = TEMPLATE)
    public static void sendToChosenCamp(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CampBlockEntity a = placeCamp(h);
        BlockPos bRel = CAMP_POS.east(6);
        h.setBlock(bRel, ModBlocks.CAMP.get());
        CampBlockEntity b = (CampBlockEntity) h.getBlockEntity(bRel);
        b.setOwner(owner(h));
        AbstractRecruitEntity r = spawnRecruit(h, bRel.east());
        Barracks.attach(r, a.getBlockPos());

        BlockPos foreign = h.absolutePos(new BlockPos(300, 1, 0));
        ToBarracksMessage.apply(level, owner(h), List.of(r.getUUID()), foreign);
        check(r.getPersistentData().getLong(Barracks.TAG_CAMP) == a.getBlockPos().asLong(), "un Campamento ajeno no debería cambiarle el suyo");

        ToBarracksMessage.apply(level, owner(h), List.of(r.getUUID()), b.getBlockPos());
        check(r.getPersistentData().getLong(Barracks.TAG_CAMP) == b.getBlockPos().asLong(), "no quedó atado al Campamento elegido");
        Barracks.checkArrivals(level);
        check(r.isRemoved(), "llegó y no se guardó");
        check(BarracksData.get(level).count(b.getBlockPos()) == 1 && BarracksData.get(level).count(a.getBlockPos()) == 0,
                "se guardó en el Campamento equivocado");
        h.succeed();
    }

    // ------------------------------------------------------------------ Campamento roto

    /** Se rompe el Campamento: salen todos, el guardia en su puesto. */
    @GameTest(template = TEMPLATE)
    public static void brokenCampReleasesEveryone(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        BlockPos campAbs = camp.getBlockPos();
        AbstractRecruitEntity g = guardAt(h, POST_POS);
        Vec3 post = g.getHoldPos();
        Barracks.store(h.getLevel(), g, campAbs, false);
        Barracks.store(h.getLevel(), spawnRecruit(h, SOLDIER_POS), campAbs, false);

        h.setBlock(CAMP_POS, Blocks.AIR);
        List<AbstractRecruitEntity> out = h.getLevel().getEntitiesOfClass(AbstractRecruitEntity.class, area(h),
                r -> r.isAlive() && Objects.equals(r.getOwnerUUID(), owner(h)));
        check(out.size() == 2, "deberían salir 2, salieron " + out.size());
        check(out.stream().anyMatch(r -> r.position().distanceTo(post) < 0.01), "el guardia no salió en su puesto");
        check(BarracksData.get(h.getLevel()).at(campAbs) == null, "el barracón del Campamento roto sigue existiendo");
        check(out.stream().noneMatch(r -> r.getPersistentData().contains(Barracks.TAG_CAMP)), "siguen atados a un Campamento que no existe");
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    private static void storeTwo(GameTestHelper h, CampBlockEntity camp) {
        for (int i = 0; i < 2; i++) {
            check(Barracks.store(h.getLevel(), spawnRecruit(h, SOLDIER_POS.east(i)), camp.getBlockPos(), false) == Barracks.StoreResult.OK,
                    "no se guardó");
        }
    }

    private static AbstractRecruitEntity guardAt(GameTestHelper h, BlockPos pos) {
        AbstractRecruitEntity g = spawnRecruit(h, pos);
        g.setHoldPos(g.position());
        g.setFollowState(3);
        check(Barracks.isGuard(g), "no cuenta como guardia");
        return g;
    }

    private static BarracksData.Soldier fakeSheet(int i) {
        return new BarracksData.Soldier(new CompoundTag(), null, false, null, 0, 0, "relleno " + i, null, 0, 0);
    }

    private static AABB area(GameTestHelper h) {
        return new AABB(h.absolutePos(BlockPos.ZERO)).inflate(16);
    }

    private static AbstractRecruitEntity only(GameTestHelper h) {
        List<AbstractRecruitEntity> found = new ArrayList<>(h.getLevel().getEntitiesOfClass(AbstractRecruitEntity.class, area(h),
                r -> r.isAlive() && Objects.equals(r.getOwnerUUID(), owner(h))));
        check(found.size() == 1, "debería haber 1 soldado en el mundo, hay " + found.size());
        return found.get(0);
    }

    private static int countIn(Container c, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
    }

    private static CampBlockEntity placeCamp(GameTestHelper h) {
        h.setBlock(CAMP_POS, ModBlocks.CAMP.get());
        if (!(h.getBlockEntity(CAMP_POS) instanceof CampBlockEntity camp)) {
            throw new GameTestAssertException("el bloque Campamento no creó su cofre");
        }
        camp.setOwner(owner(h));
        return camp;
    }

    private static AbstractRecruitEntity spawnRecruit(GameTestHelper h, BlockPos pos) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
        check(type != null, "Recruits no está cargado");
        Entity e = h.spawn(type, pos);
        check(e instanceof AbstractRecruitEntity, "recruits:recruit no es un AbstractRecruitEntity");
        AbstractRecruitEntity recruit = (AbstractRecruitEntity) e;
        for (EquipmentSlot slot : EquipmentSlot.values()) recruit.setItemSlot(slot, ItemStack.EMPTY);
        recruit.getInventory().clearContent();
        recruit.setOwnerUUID(Optional.of(owner(h)));
        recruit.setIsOwned(true);
        return recruit;
    }

    /** Un "jugador" distinto por prueba (las pruebas corren en paralelo, una al lado de la otra). */
    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h8@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
