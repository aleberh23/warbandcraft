package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.kit.ClassAssigner;
import com.ale.warbandcraft.kit.ClassKit;
import com.ale.warbandcraft.kit.KitManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pruebas automáticas del Hito 1 (Campamento + clases). Corren sin pantalla con
 * {@code gradlew runGameTestServer} (o probar.bat). No se cargan en partidas normales.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H1GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CAMP_POS = new BlockPos(1, 1, 1);
    private static final BlockPos SOLDIER_POS = new BlockPos(4, 1, 4);

    // ------------------------------------------------------------------ una prueba por clase

    @GameTest(template = TEMPLATE) public static void kitInfante(GameTestHelper h)    { checkKit(h, "infante"); }
    @GameTest(template = TEMPLATE) public static void kitLancero(GameTestHelper h)    { checkKit(h, "lancero"); }
    @GameTest(template = TEMPLATE) public static void kitArquero(GameTestHelper h)    { checkKit(h, "arquero"); }
    @GameTest(template = TEMPLATE) public static void kitBallestero(GameTestHelper h) { checkKit(h, "ballestero"); }
    @GameTest(template = TEMPLATE) public static void kitCaballero(GameTestHelper h)  { checkKit(h, "caballero"); }
    @GameTest(template = TEMPLATE) public static void kitZapador(GameTestHelper h)    { checkKit(h, "zapador"); }
    @GameTest(template = TEMPLATE) public static void kitAbanderado(GameTestHelper h) { checkKit(h, "abanderado"); }

    /**
     * Campamento con justo lo del nivel Básico + soldado raso de Recruits → se asigna la clase,
     * cambia a la entidad correcta, queda con el equipo y munición, y el cofre queda vacío.
     */
    private static void checkKit(GameTestHelper h, String kitName) {
        ClassKit kit = kit(kitName);
        ClassKit.Tier tier = kit.tiers().get(0);
        CampBlockEntity camp = placeCamp(h);
        fill(camp, tier.cost());
        AbstractRecruitEntity recruit = spawnRecruit(h);

        ClassAssigner.Result r = ClassAssigner.assign(h.getLevel(), owner(h), recruit, kit.id());
        check(r.ok(), kitName + ": la asignación falló: " + r.message().getString());
        AbstractRecruitEntity soldier = r.soldier();
        check(soldier != null && soldier.isAlive(), kitName + ": no hay soldado vivo después de asignar");
        check(soldier.getFollowState() == ClassAssigner.FOLLOW_OWNER,
                kitName + ": después de armarlo debería seguir al jugador (estado " + soldier.getFollowState() + ")");

        // Conversión de entidad
        EntityType<?> expected = ForgeRegistries.ENTITY_TYPES.getValue(kit.entity());
        check(soldier.getType() == expected, kitName + ": entidad " + key(soldier.getType()) + ", se esperaba " + kit.entity());
        if (expected != recruit.getType()) {
            check(recruit.isRemoved(), kitName + ": el soldado viejo no se borró");
            check(soldier.getUUID() != recruit.getUUID(), kitName + ": la entidad nueva reutilizó el UUID");
        }
        check(owner(h).equals(soldier.getOwnerUUID()), kitName + ": la entidad nueva perdió el dueño");
        check(soldier.isOwned(), kitName + ": la entidad nueva no figura como contratada");
        check(countRecruits(h) == 1, kitName + ": hay " + countRecruits(h) + " soldados en la zona (debería haber 1)");

        // Equipo puesto
        for (Map.Entry<EquipmentSlot, Item> e : tier.equipment().entrySet()) {
            ItemStack worn = soldier.getItemBySlot(e.getKey());
            check(worn.is(e.getValue()), kitName + ": en " + e.getKey().getName() + " tiene " + key(worn.getItem()) + ", se esperaba " + key(e.getValue()));
        }
        // Munición / consumibles en la mochila
        for (ItemStack stack : tier.inventory()) {
            int have = backpackCount(soldier.getInventory(), stack.getItem());
            check(have >= stack.getCount(), kitName + ": tiene " + have + " " + key(stack.getItem()) + " en la mochila, se esperaban " + stack.getCount());
        }
        // Caballo
        if (tier.horse()) {
            check(soldier.getVehicle() instanceof Horse, kitName + ": debería estar montado a caballo");
            Horse horse = (Horse) soldier.getVehicle();
            check(horse.isSaddled() && horse.isTamed(), kitName + ": el caballo no está ensillado/domado");
        }
        // Clase guardada en el soldado
        check(kit.id().toString().equals(soldier.getPersistentData().getString(ClassAssigner.TAG_CLASS)), kitName + ": no quedó guardada la clase en el soldado");
        // Se cobró todo
        check(camp.isEmpty(), kitName + ": el Campamento debería quedar vacío y le queda: " + contents(camp));
        h.succeed();
    }

    // ------------------------------------------------------------------ casos especiales

    /** El equipo que el soldado tenía antes vuelve al Campamento (y no se cobra lo que ya traía). */
    @GameTest(template = TEMPLATE)
    public static void oldGearGoesBackToCamp(GameTestHelper h) {
        ClassKit kit = kit("infante");
        CampBlockEntity camp = placeCamp(h);
        fill(camp, kit.tiers().get(0).cost());
        AbstractRecruitEntity recruit = spawnRecruit(h);
        recruit.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
        recruit.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SWORD));
        recruit.getInventory().setItem(8, new ItemStack(Items.BREAD, 5));

        ClassAssigner.Result r = ClassAssigner.assign(h.getLevel(), owner(h), recruit, kit.id());
        check(r.ok(), "la asignación falló: " + r.message().getString());
        check(camp.count(Items.GOLDEN_HELMET) == 1, "el casco de oro viejo no volvió al Campamento: " + contents(camp));
        check(camp.count(Items.WOODEN_SWORD) == 1, "la espada de madera vieja no volvió al Campamento: " + contents(camp));
        check(camp.count(Items.BREAD) == 5, "el pan de la mochila no volvió al Campamento: " + contents(camp));
        check(r.soldier().getItemBySlot(EquipmentSlot.HEAD).is(Items.LEATHER_HELMET), "no se puso el casco nuevo");
        h.succeed();
    }

    /** El equipo viejo cuenta como recurso: un soldado con escudo no necesita otro escudo del cofre. */
    @GameTest(template = TEMPLATE)
    public static void oldGearCountsAsResource(GameTestHelper h) {
        ClassKit kit = kit("infante");
        CampBlockEntity camp = placeCamp(h);
        Map<Item, Integer> cost = new java.util.LinkedHashMap<>(kit.tiers().get(0).cost());
        cost.remove(Items.SHIELD);
        fill(camp, cost);
        AbstractRecruitEntity recruit = spawnRecruit(h);
        recruit.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));

        ClassAssigner.Result r = ClassAssigner.assign(h.getLevel(), owner(h), recruit, kit.id());
        check(r.ok(), "debería alcanzar con el escudo que ya tenía: " + r.message().getString());
        check(r.soldier().getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD), "perdió el escudo");
        check(camp.isEmpty(), "sobró algo en el Campamento: " + contents(camp));
        h.succeed();
    }

    /** Un soldado con experiencia y un Campamento rico recibe el mejor nivel. */
    @GameTest(template = TEMPLATE)
    public static void veteranGetsBestTier(GameTestHelper h) {
        ClassKit kit = kit("infante");
        CampBlockEntity camp = placeCamp(h);
        for (ClassKit.Tier t : kit.tiers()) fill(camp, t.cost());
        AbstractRecruitEntity recruit = spawnRecruit(h);
        recruit.setXpLevel(10);

        ClassAssigner.Result r = ClassAssigner.assign(h.getLevel(), owner(h), recruit, kit.id());
        check(r.ok(), "falló: " + r.message().getString());
        check(r.soldier().getPersistentData().getInt(ClassAssigner.TAG_TIER) == kit.tiers().size() - 1, "no eligió el nivel Élite");
        check(r.soldier().getItemBySlot(EquipmentSlot.FEET).is(Items.IRON_BOOTS), "no tiene las botas de hierro de Élite");
        h.succeed();
    }

    /** Un recluta nuevo no recibe equipo de Élite aunque el Campamento lo tenga. */
    @GameTest(template = TEMPLATE)
    public static void rookieGetsBasicTier(GameTestHelper h) {
        ClassKit kit = kit("infante");
        CampBlockEntity camp = placeCamp(h);
        for (ClassKit.Tier t : kit.tiers()) fill(camp, t.cost());
        AbstractRecruitEntity recruit = spawnRecruit(h);
        recruit.setXpLevel(0);

        ClassAssigner.Result r = ClassAssigner.assign(h.getLevel(), owner(h), recruit, kit.id());
        check(r.ok(), "falló: " + r.message().getString());
        check(r.soldier().getPersistentData().getInt(ClassAssigner.TAG_TIER) == 0, "un recluta nivel 0 recibió un nivel superior");
        h.succeed();
    }

    /** Campamento vacío → no cambia nada y el mensaje dice exactamente qué falta. */
    @GameTest(template = TEMPLATE)
    public static void missingResourcesMessage(GameTestHelper h) {
        ClassKit kit = kit("arquero");
        CampBlockEntity camp = placeCamp(h);
        camp.insert(new ItemStack(Items.ARROW, 10));
        AbstractRecruitEntity recruit = spawnRecruit(h);

        ClassAssigner.Result r = ClassAssigner.assign(h.getLevel(), owner(h), recruit, kit.id());
        check(!r.ok(), "no debería poder armarse sin recursos");
        check(translationKey(r.message()).equals("msg.warbandcraft.missing"), "mensaje equivocado: " + translationKey(r.message()));
        Object[] args = ((TranslatableContents) r.message().getContents()).getArgs();
        String list = args[1] instanceof Component c ? c.getString() : String.valueOf(args[1]);
        check(list.contains("22x"), "debería pedir las 22 flechas que faltan (32 - 10): " + list);
        check(list.contains("1x") && list.toLowerCase().contains("bow"), "debería pedir 1 arco: " + list);
        check(list.contains("Leather Tunic"), "debería pedir la pechera de cuero: " + list);
        check(!recruit.isRemoved() && recruit.getType() == recruitType(), "el soldado no debería cambiar");
        check(camp.count(Items.ARROW) == 10, "no debería cobrar nada");
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void noCampMessage(GameTestHelper h) {
        AbstractRecruitEntity recruit = spawnRecruit(h);
        ClassAssigner.Result r = ClassAssigner.assign(h.getLevel(), owner(h), recruit, kit("infante").id());
        check(!r.ok() && translationKey(r.message()).equals("msg.warbandcraft.no_camp"), "esperaba 'no hay Campamento', vino: " + translationKey(r.message()));
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void notYourSoldier(GameTestHelper h) {
        ClassKit kit = kit("infante");
        fill(placeCamp(h), kit.tiers().get(0).cost());
        AbstractRecruitEntity recruit = spawnRecruit(h);
        ClassAssigner.Result r = ClassAssigner.assign(h.getLevel(), UUID.randomUUID(), recruit, kit.id());
        check(!r.ok() && translationKey(r.message()).equals("msg.warbandcraft.not_yours"), "esperaba 'no es tuyo', vino: " + translationKey(r.message()));
        h.succeed();
    }

    /** Cambiar de clase dos veces: arquero → infante devuelve el arco y las flechas. */
    @GameTest(template = TEMPLATE)
    public static void reassignClass(GameTestHelper h) {
        ClassKit arquero = kit("arquero");
        ClassKit infante = kit("infante");
        CampBlockEntity camp = placeCamp(h);
        fill(camp, arquero.tiers().get(0).cost());
        fill(camp, infante.tiers().get(0).cost());
        AbstractRecruitEntity recruit = spawnRecruit(h);

        ClassAssigner.Result r1 = ClassAssigner.assign(h.getLevel(), owner(h), recruit, arquero.id());
        check(r1.ok(), "arquero falló: " + r1.message().getString());
        ClassAssigner.Result r2 = ClassAssigner.assign(h.getLevel(), owner(h), r1.soldier(), infante.id());
        check(r2.ok(), "infante falló: " + r2.message().getString());
        check(r2.soldier().getType() == ForgeRegistries.ENTITY_TYPES.getValue(infante.entity()), "no volvió a ser infante");
        check(camp.count(Items.BOW) == 1, "el arco no volvió: " + contents(camp));
        check(camp.count(Items.ARROW) == 32, "las flechas no volvieron: " + contents(camp));
        check(countRecruits(h) == 1, "quedaron soldados duplicados: " + countRecruits(h));
        h.succeed();
    }

    // ------------------------------------------------------------------ utilidades

    private static ClassKit kit(String name) {
        ClassKit kit = KitManager.INSTANCE.get(new ResourceLocation(WarbandCraft.MODID, name));
        check(kit != null, "no se cargó la clase " + name + " (¿falla el JSON?)");
        check(kit.tiers().get(0).valid(), "el nivel Básico de " + name + " es inválido");
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

    private static void fill(CampBlockEntity camp, Map<Item, Integer> cost) {
        cost.forEach((item, count) -> {
            int left = count;
            while (left > 0) {
                int n = Math.min(left, item.getMaxStackSize());
                ItemStack rest = camp.insert(new ItemStack(item, n));
                check(rest.isEmpty(), "el Campamento se llenó");
                left -= n;
            }
        });
    }

    private static EntityType<?> recruitType() {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
        check(type != null, "Recruits no está cargado");
        return type;
    }

    private static AbstractRecruitEntity spawnRecruit(GameTestHelper h) {
        Entity e = h.spawn(recruitType(), SOLDIER_POS);
        check(e instanceof AbstractRecruitEntity, "recruits:recruit no es un AbstractRecruitEntity");
        AbstractRecruitEntity recruit = (AbstractRecruitEntity) e;
        for (EquipmentSlot slot : EquipmentSlot.values()) recruit.setItemSlot(slot, ItemStack.EMPTY);
        recruit.getInventory().clearContent();
        recruit.setOwnerUUID(java.util.Optional.of(owner(h)));
        recruit.setIsOwned(true);
        return recruit;
    }

    /**
     * Las pruebas corren en paralelo, una al lado de la otra: cada una usa un "jugador" distinto
     * (derivado de su posición) para que no se mezclen Campamentos ni soldados.
     */
    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-test@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    /** Soldados vivos de esta prueba (del dueño de esta prueba). */
    private static int countRecruits(GameTestHelper h) {
        AABB box = new AABB(h.absolutePos(BlockPos.ZERO)).inflate(48);
        UUID owner = owner(h);
        List<AbstractRecruitEntity> list = h.getLevel().getEntitiesOfClass(AbstractRecruitEntity.class, box,
                r -> r.isAlive() && owner.equals(r.getOwnerUUID()));
        return list.size();
    }

    private static int backpackCount(SimpleContainer inv, Item item) {
        int n = 0;
        for (int i = 6; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
        }
        return n;
    }

    private static String contents(CampBlockEntity camp) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < camp.getContainerSize(); i++) {
            ItemStack s = camp.getItem(i);
            if (!s.isEmpty()) sb.append(s.getCount()).append("x ").append(key(s.getItem())).append(", ");
        }
        return sb.append("]").toString();
    }

    private static String translationKey(Component c) {
        return c.getContents() instanceof TranslatableContents t ? t.getKey() : c.getString();
    }

    private static String key(Item item) {
        return String.valueOf(ForgeRegistries.ITEMS.getKey(item));
    }

    private static String key(EntityType<?> type) {
        return String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(type));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
