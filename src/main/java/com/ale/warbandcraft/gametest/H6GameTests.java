package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.faction.Doctrine;
import com.ale.warbandcraft.faction.PlayerFactionData;
import com.ale.warbandcraft.kit.KitOverride;
import com.ale.warbandcraft.kit.PlayerKits;
import com.ale.warbandcraft.network.ChooseDoctrineMessage;
import com.ale.warbandcraft.network.SapperOrderMessage;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.sapper.FieldCannonEntity;
import com.ale.warbandcraft.sapper.SapperRules;
import com.ale.warbandcraft.village.VillageData;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import java.util.EnumMap;
import java.util.List;
import com.ale.warbandcraft.kit.ClassAssigner;
import com.ale.warbandcraft.kit.ClassKit;
import com.ale.warbandcraft.kit.KitManager;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.world.RecruitsFactionManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pruebas del Hito 6 (tu facción): cofres vinculados, colores/estandarte de tu facción de Recruits,
 * kits propios con re-armado, doctrinas y Zapador (TNT y cañón).
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H6GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CAMP_POS = new BlockPos(1, 1, 1);
    private static final BlockPos CHEST_POS = new BlockPos(3, 1, 1);
    private static final BlockPos SOLDIER_POS = new BlockPos(4, 1, 4);

    // ------------------------------------------------------------------ cofres vinculados

    /** Con el cofre del Campamento vacío, el soldado se arma con lo que hay en un cofre vinculado. */
    @GameTest(template = TEMPLATE)
    public static void linkedChestPaysForKit(GameTestHelper h) {
        ClassKit kit = kit("infante");
        CampBlockEntity camp = placeCamp(h);
        Container chest = placeChest(h, CHEST_POS);
        check(camp.toggleLink(h.absolutePos(CHEST_POS)) == CampBlockEntity.LinkResult.LINKED, "no se vinculó el cofre");
        fill(chest, kit.tiers().get(0).cost());

        ClassAssigner.Result r = ClassAssigner.assign(h.getLevel(), owner(h), spawnRecruit(h), kit.id());
        check(r.ok(), "la asignación falló con el cofre vinculado: " + r.message().getString());
        check(chest.isEmpty(), "el cofre vinculado debería quedar vacío");
        h.succeed();
    }

    /** Con el cofre del Campamento lleno, lo que llega (botín, equipo viejo) va al cofre vinculado. */
    @GameTest(template = TEMPLATE)
    public static void overflowGoesToLinkedChest(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        for (int i = 0; i < camp.getContainerSize(); i++) camp.setItem(i, new ItemStack(Items.DIRT, 64));
        Container chest = placeChest(h, CHEST_POS);
        camp.toggleLink(h.absolutePos(CHEST_POS));

        ItemStack rest = camp.insert(new ItemStack(Items.ARROW, 10));
        check(rest.isEmpty(), "no entró nada aunque hay un cofre vinculado vacío");
        check(count(chest, Items.ARROW) == 10, "las flechas no fueron al cofre vinculado");
        check(camp.count(Items.ARROW) == 10, "count() no suma el cofre vinculado");
        h.succeed();
    }

    /** Vincular un cofre doble vincula sus dos mitades; volver a tocarlo lo desvincula entero. */
    @GameTest(template = TEMPLATE)
    public static void doubleChestLinksBothHalves(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        BlockPos left = CHEST_POS, right = CHEST_POS.east();
        // Mirando al norte, la mitad LEFT se une con la del este y la RIGHT con la del oeste.
        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        h.setBlock(left, chest.setValue(ChestBlock.TYPE, ChestType.LEFT));
        h.setBlock(right, chest.setValue(ChestBlock.TYPE, ChestType.RIGHT));

        check(camp.toggleLink(h.absolutePos(left)) == CampBlockEntity.LinkResult.LINKED, "no se vinculó el cofre doble");
        check(camp.getLinked().size() == 2, "deberían quedar vinculadas las 2 mitades, hay " + camp.getLinked().size());
        check(camp.linkedSlots() == 54, "un cofre doble suma 54 espacios, suma " + camp.linkedSlots());
        check(camp.toggleLink(h.absolutePos(right)) == CampBlockEntity.LinkResult.UNLINKED, "tocar la otra mitad debería desvincular");
        check(camp.getLinked().isEmpty(), "quedaron mitades vinculadas");
        h.succeed();
    }

    /** Un cofre vinculado que se rompe se olvida solo, y lo que no es un cofre no se vincula. */
    @GameTest(template = TEMPLATE)
    public static void brokenChestIsForgotten(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        placeChest(h, CHEST_POS);
        camp.toggleLink(h.absolutePos(CHEST_POS));
        h.setBlock(CHEST_POS, Blocks.AIR);
        check(camp.storages().size() == 1, "el cofre roto sigue contando como almacén");
        check(camp.getLinked().isEmpty(), "el cofre roto sigue en la lista de vinculados");

        h.setBlock(CHEST_POS, Blocks.FURNACE);
        check(camp.toggleLink(h.absolutePos(CHEST_POS)) == CampBlockEntity.LinkResult.NOT_A_CHEST, "un horno no se debería poder vincular");
        h.succeed();
    }

    // ------------------------------------------------------------------ estilo de tu facción

    /** El Abanderado lleva el estandarte de la facción de Recruits del dueño y el cuero sale de su color. */
    @GameTest(template = TEMPLATE)
    public static void standardBearerWearsYourBanner(GameTestHelper h) {
        RecruitsFactionManager manager = FactionEvents.recruitsFactionManager;
        check(manager != null, "el manager de facciones de Recruits no existe");

        ItemStack banner = new ItemStack(Items.RED_BANNER);
        CompoundTag pattern = new CompoundTag();
        pattern.putString("Pattern", "cr"); // cruz
        pattern.putInt("Color", 15);
        ListTag patterns = new ListTag();
        patterns.add(pattern);
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.put("Patterns", patterns);
        banner.addTagElement("BlockEntityTag", blockEntity);

        String teamId = "wbtest" + Integer.toHexString(owner(h).hashCode());
        byte red = 11; // "rojo claro" en la lista de colores de unidad de Recruits
        manager.addTeam(teamId, "Prueba", owner(h), "", banner.serializeNBT(), red, ChatFormatting.RED);
        Scoreboard scoreboard = h.getLevel().getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(teamId);
        if (team == null) team = scoreboard.addPlayerTeam(teamId);

        ClassKit kit = kit("abanderado");
        CampBlockEntity camp = placeCamp(h);
        fill(camp, kit.tiers().get(0).cost());
        AbstractRecruitEntity recruit = spawnRecruit(h);
        scoreboard.addPlayerToTeam(recruit.getScoreboardName(), team);

        ClassAssigner.Result r = ClassAssigner.assign(h.getLevel(), owner(h), recruit, kit.id());
        check(r.ok(), "la asignación falló: " + r.message().getString());
        AbstractRecruitEntity soldier = r.soldier();
        ItemStack head = soldier.getItemBySlot(EquipmentSlot.HEAD);
        check(ItemStack.isSameItemSameTags(head, banner), "el Abanderado no lleva el estandarte de la facción, lleva " + head);
        ItemStack chest = soldier.getItemBySlot(EquipmentSlot.CHEST);
        check(chest.getItem() instanceof DyeableLeatherItem d && d.getColor(chest) == 0xB91515,
                "el cuero no salió del color de la facción");
        h.succeed();
    }

    // ------------------------------------------------------------------ kits propios

    /** Un kit propio pisa el equipo del JSON, y el Campamento cobra lo nuevo (no lo viejo). */
    @GameTest(template = TEMPLATE)
    public static void customKitReplacesGear(GameTestHelper h) {
        ClassKit base = kit("infante");
        saveAxeKit(h, base);
        ClassKit mine = PlayerKits.resolve(h.getLevel().getServer(), owner(h), base.id());
        check(mine.tiers().get(0).equipment().get(EquipmentSlot.MAINHAND) == Items.IRON_AXE, "resolve no mezcló el kit propio");

        CampBlockEntity camp = placeCamp(h);
        fill(camp, mine.tiers().get(0).cost());
        ClassAssigner.Result r = ClassAssigner.assign(h.getLevel(), owner(h), spawnRecruit(h), base.id());
        check(r.ok(), "la asignación con kit propio falló: " + r.message().getString());
        check(r.soldier().getMainHandItem().is(Items.IRON_AXE), "el soldado no recibió el hacha del kit propio");
        check(camp.isEmpty(), "el Campamento debería quedar vacío (cobró otra cosa)");
        h.succeed();
    }

    /** Las reglas que protegen la IA de Recruits: arquero con arco y flechas, infante sin arco. */
    @GameTest(template = TEMPLATE)
    public static void kitRulesProtectAi(GameTestHelper h) {
        ResourceLocation bowman = new ResourceLocation("recruits", "bowman");
        ResourceLocation recruit = new ResourceLocation("recruits", "recruit");
        List<ItemStack> arrows = List.of(new ItemStack(Items.ARROW, 16));
        check(PlayerKits.validate(bowman, Map.of(EquipmentSlot.MAINHAND, Items.IRON_SWORD), arrows) != null, "un arquero con espada pasó");
        check(PlayerKits.validate(bowman, Map.of(EquipmentSlot.MAINHAND, Items.BOW), List.of()) != null, "un arquero sin flechas pasó");
        check(PlayerKits.validate(bowman, Map.of(EquipmentSlot.MAINHAND, Items.BOW), arrows) == null, "un arquero con arco y flechas no pasó");
        check(PlayerKits.validate(recruit, Map.of(EquipmentSlot.MAINHAND, Items.BOW), arrows) != null, "un infante con arco pasó");
        check(PlayerKits.validate(recruit, Map.of(EquipmentSlot.MAINHAND, Items.IRON_AXE), List.of()) == null, "un infante con hacha no pasó");
        h.succeed();
    }

    /** Guardar un kit re-arma solo al soldado cerca del Campamento; sin materiales queda como estaba. */
    @GameTest(template = TEMPLATE)
    public static void savingKitRearmsSoldiers(GameTestHelper h) {
        ClassKit base = kit("infante");
        CampBlockEntity camp = placeCamp(h);
        fill(camp, base.tiers().get(0).cost());
        ClassAssigner.Result r = ClassAssigner.assign(h.getLevel(), owner(h), spawnRecruit(h), base.id());
        check(r.ok(), "la asignación falló: " + r.message().getString());
        AbstractRecruitEntity soldier = r.soldier();
        Item oldWeapon = soldier.getMainHandItem().getItem();
        int followState = soldier.getFollowState();

        saveAxeKit(h, base);
        PlayerKits.rearmNear(h.getLevel(), owner(h), true);
        check(soldier.getMainHandItem().is(oldWeapon), "sin hacha en el Campamento no debería cambiar de arma");

        camp.insert(new ItemStack(Items.IRON_AXE));
        PlayerKits.rearmNear(h.getLevel(), owner(h), true);
        check(soldier.getMainHandItem().is(Items.IRON_AXE), "no se re-armó con el hacha");
        check(camp.count(oldWeapon) == 1, "el arma vieja no volvió al Campamento");
        check(soldier.getFollowState() == followState, "el re-armado le cambió las órdenes");
        check(!PlayerKits.outdated(h.getLevel(), owner(h), soldier), "quedó marcado con el kit viejo");
        h.succeed();
    }

    // ------------------------------------------------------------------ doctrinas

    /** Escudos pone +3 de armadura una sola vez (aunque se aplique de nuevo) y se saca al cambiar. */
    @GameTest(template = TEMPLATE)
    public static void shieldsDoctrineAppliesOnce(GameTestHelper h) {
        ClassKit base = kit("infante");
        CampBlockEntity camp = placeCamp(h);
        fill(camp, base.tiers().get(0).cost());
        AbstractRecruitEntity recruit = spawnRecruit(h);
        double before = recruit.getAttributeBaseValue(Attributes.ARMOR);
        PlayerFactionData.get(h.getLevel().getServer()).setDoctrine(owner(h), Doctrine.SHIELDS, h.getLevel().getGameTime());

        AbstractRecruitEntity soldier = ClassAssigner.assign(h.getLevel(), owner(h), recruit, base.id()).soldier();
        check(soldier != null, "la asignación falló");
        double bonus = armorModifiers(soldier);
        check(bonus == Doctrine.SHIELDS_ARMOR_BONUS, "la doctrina Escudos debería sumar " + Doctrine.SHIELDS_ARMOR_BONUS + " y suma " + bonus);
        Doctrine.apply(soldier, Doctrine.SHIELDS);
        check(armorModifiers(soldier) == Doctrine.SHIELDS_ARMOR_BONUS, "aplicar dos veces apiló el bono");
        Doctrine.apply(soldier, Doctrine.CAVALRY);
        check(armorModifiers(soldier) == 0, "cambiar de doctrina no sacó el bono de Escudos");
        check(soldier.getAttributeBaseValue(Attributes.ARMOR) == before, "se tocó la armadura base");
        h.succeed();
    }

    /** La primera doctrina es gratis; después hay que esperar un día y pagar 20 esmeraldas. */
    @GameTest(template = TEMPLATE)
    public static void doctrineChangeCosts(GameTestHelper h) {
        FakePlayer player = FakePlayerFactory.get(h.getLevel(), new GameProfile(owner(h), "wb_h6_doctrina"));
        CampBlockEntity camp = placeCamp(h);
        PlayerFactionData data = PlayerFactionData.get(h.getLevel().getServer());

        ChooseDoctrineMessage.choose(player, camp, Doctrine.CAVALRY);
        check(data.doctrine(owner(h)) == Doctrine.CAVALRY, "la primera doctrina (gratis) no quedó");

        ChooseDoctrineMessage.choose(player, camp, Doctrine.SHIELDS);
        check(data.doctrine(owner(h)) == Doctrine.CAVALRY, "cambió de doctrina sin esperar el día");

        // Como si hubiera pasado un día
        data.setDoctrine(owner(h), Doctrine.CAVALRY, h.getLevel().getGameTime() - Doctrine.CHANGE_WAIT_TICKS);
        ChooseDoctrineMessage.choose(player, camp, Doctrine.SHIELDS);
        check(data.doctrine(owner(h)) == Doctrine.CAVALRY, "cambió de doctrina sin esmeraldas");

        camp.insert(new ItemStack(Items.EMERALD, Doctrine.CHANGE_COST_EMERALDS + 1));
        ChooseDoctrineMessage.choose(player, camp, Doctrine.SHIELDS);
        check(data.doctrine(owner(h)) == Doctrine.SHIELDS, "con un día y esmeraldas no cambió de doctrina");
        check(camp.count(Items.EMERALD) == 1, "debería haber cobrado " + Doctrine.CHANGE_COST_EMERALDS + " esmeraldas");
        h.succeed();
    }

    // ------------------------------------------------------------------ zapador

    /**
     * Orden de volar contra una aldea de otra facción: el Zapador gasta 1 TNT, aparece un TNT encendido
     * junto al bloque y empieza el ataque. (El TNT se borra antes de explotar para no romper otras pruebas.)
     */
    @GameTest(template = TEMPLATE, batch = "h6_zapador", timeoutTicks = 200)
    public static void sapperPlantsTnt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = h.absolutePos(CHEST_POS);
        VillageData.Village village = new VillageData.Village(center, new ResourceLocation(WarbandCraft.MODID, "valdren"), level.getGameTime());
        VillageData.get(level).add(village);
        BlockPos wall = new BlockPos(4, 1, 2);
        h.setBlock(wall, Blocks.OAK_PLANKS);

        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "siege_engineer"));
        check(type != null, "no existe recruits:siege_engineer");
        AbstractRecruitEntity sapper = (AbstractRecruitEntity) h.spawn(type, SOLDIER_POS);
        sapper.setOwnerUUID(Optional.of(owner(h)));
        sapper.setIsOwned(true);
        sapper.getInventory().setItem(6, new ItemStack(Items.TNT, 2));

        // Contra un lugar sin aldea no se puede
        BlockPos nowhere = h.absolutePos(wall).offset(500, 0, 500);
        SapperOrderMessage.apply(level, owner(h), List.of(sapper.getUUID()), false, nowhere);
        check(!sapper.getPersistentData().contains(SapperRules.TAG_BLAST), "aceptó volar algo fuera de una aldea");

        SapperOrderMessage.apply(level, owner(h), List.of(sapper.getUUID()), false, h.absolutePos(wall));
        check(sapper.getPersistentData().contains(SapperRules.TAG_BLAST), "no tomó la orden de volar");

        h.succeedWhen(() -> {
            List<PrimedTnt> tnt = level.getEntitiesOfClass(PrimedTnt.class, new AABB(h.absolutePos(wall)).inflate(4));
            check(!tnt.isEmpty(), "todavía no puso el TNT");
            tnt.forEach(Entity::discard);
            check(sapper.getInventory().countItem(Items.TNT) == 1, "debería haber gastado 1 TNT");
            check(RaidManager.get(level, center) != null, "volar la aldea no empezó el ataque");
            RaidManager.discard(level, center);
            VillageData.get(level).add(village.withLootedUntil(Long.MAX_VALUE)); // que no quede activa
            sapper.discard();
        });
    }

    /** El tiro del cañón cae donde se apuntó (misma física que el TNT de vanilla). */
    @GameTest(template = TEMPLATE)
    public static void cannonArcLandsOnTarget(GameTestHelper h) {
        Vec3[] deltas = {new Vec3(30, 0, 10), new Vec3(-12, 6, 25), new Vec3(5, -8, -38)};
        for (Vec3 delta : deltas) {
            double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            int ticks = Math.max(20, Math.min(80, (int) (20 + horizontal * 0.9)));
            Vec3 v = FieldCannonEntity.launchVelocity(delta, ticks);
            Vec3 pos = Vec3.ZERO;
            for (int i = 0; i < ticks; i++) { // PrimedTnt.tick: gravedad, mover, rozamiento
                v = v.add(0, -0.04, 0);
                pos = pos.add(v);
                v = v.scale(0.98);
            }
            check(pos.distanceTo(delta) < 0.1, "el tiro a " + delta + " cayó en " + pos);
        }
        h.succeed();
    }

    private static void saveAxeKit(GameTestHelper h, ClassKit base) {
        Map<EquipmentSlot, Item> eq = new EnumMap<>(EquipmentSlot.class);
        eq.putAll(base.tiers().get(0).equipment());
        eq.put(EquipmentSlot.MAINHAND, Items.IRON_AXE);
        PlayerFactionData.get(h.getLevel().getServer()).setOverride(owner(h), base.id(), 0,
                new KitOverride(eq, base.tiers().get(0).inventory()));
    }

    private static double armorModifiers(AbstractRecruitEntity soldier) {
        var inst = soldier.getAttribute(Attributes.ARMOR);
        double total = 0;
        if (inst != null) for (var m : inst.getModifiers()) if (m.getName().startsWith("Doctrina")) total += m.getAmount();
        return total;
    }

    // ------------------------------------------------------------------ ayudas

    private static ClassKit kit(String name) {
        ClassKit kit = KitManager.INSTANCE.get(new ResourceLocation(WarbandCraft.MODID, name));
        check(kit != null, "no se cargó la clase " + name);
        return kit;
    }

    private static CampBlockEntity placeCamp(GameTestHelper h) {
        h.setBlock(CAMP_POS, ModBlocks.CAMP.get());
        if (!(h.getBlockEntity(CAMP_POS) instanceof CampBlockEntity camp)) {
            throw new GameTestAssertException("el bloque Campamento no creó su cofre");
        }
        camp.setOwner(owner(h));
        return camp;
    }

    private static Container placeChest(GameTestHelper h, BlockPos pos) {
        h.setBlock(pos, Blocks.CHEST);
        if (!(h.getBlockEntity(pos) instanceof Container chest)) throw new GameTestAssertException("no se creó el cofre");
        return chest;
    }

    private static void fill(Container container, Map<Item, Integer> cost) {
        int slot = 0;
        for (Map.Entry<Item, Integer> e : cost.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                int n = Math.min(left, e.getKey().getMaxStackSize());
                check(slot < container.getContainerSize(), "no entra el costo en el cofre");
                container.setItem(slot++, new ItemStack(e.getKey(), n));
                left -= n;
            }
        }
    }

    private static int count(Container container, Item item) {
        int n = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) n += container.getItem(i).getCount();
        }
        return n;
    }

    private static AbstractRecruitEntity spawnRecruit(GameTestHelper h) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
        check(type != null, "Recruits no está cargado");
        Entity e = h.spawn(type, SOLDIER_POS);
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
        return UUID.nameUUIDFromBytes(("warbandcraft-h6@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
