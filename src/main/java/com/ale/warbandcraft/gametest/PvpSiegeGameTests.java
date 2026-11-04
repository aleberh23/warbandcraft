package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.raid.PvpSiege;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.village.Territory;
import com.ale.warbandcraft.village.VillageData;
import com.mojang.authlib.GameProfile;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;
import java.util.UUID;

/**
 * Pedido de Ale (4/11): sin asedios de Recruits; la aldea de otro jugador se gana con el asedio de Warband. Defienden los
 * soldados del dueño que están ahí; sin ninguno, la aldea y su Campamento pasan al atacante y el barracón del que la
 * pierde se muda a otro Campamento suyo.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class PvpSiegeGameTests {
    private static final String TEMPLATE = "empty";

    @GameTest(template = TEMPLATE)
    public static void pvpSiegeTakesVillageAndCamp(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Faction f = FactionManager.INSTANCE.ordered().get(0);
        String tag = h.absolutePos(BlockPos.ZERO).toShortString();
        UUID defender = UUID.nameUUIDFromBytes(("pvp-defender@" + tag).getBytes());
        FakePlayer attacker = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(("pvp-attacker@" + tag).getBytes()), "pvp_attacker"));
        BlockPos center = h.absolutePos(new BlockPos(4, 2, 4));
        BlockPos villageCamp = h.absolutePos(new BlockPos(1, 2, 1));
        BlockPos otherCamp = h.absolutePos(new BlockPos(7, 2, 7));
        VillageData data = VillageData.get(level);
        VillageData.Village v = new VillageData.Village(center, f.id(), level.getGameTime()).withOwner(defender);
        data.add(v);
        try {
            CampBlockEntity vc = camp(h, villageCamp, defender);
            vc.setVillage(center);
            data.setStorehouse(center, villageCamp);
            camp(h, otherCamp, defender);

            // Uno guardado en el barracón de la aldea, uno defendiendo y un trabajador de la aldea
            AbstractRecruitEntity stored = soldier(h, defender, new BlockPos(3, 2, 5));
            check(Barracks.store(level, stored, villageCamp, false) == Barracks.StoreResult.OK, "no se pudo guardar al soldado en el barracón de la aldea");
            AbstractRecruitEntity guard = soldier(h, defender, new BlockPos(5, 2, 5));
            EntityType<?> farmerType = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("workers", "farmer"));
            check(farmerType != null, "Villager Workers no está cargado");
            AbstractRecruitEntity worker = (AbstractRecruitEntity) h.spawn(farmerType, new BlockPos(2, 2, 6));
            worker.setOwnerUUID(Optional.of(defender));
            worker.setIsOwned(true);

            attacker.moveTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
            RaidManager.Raid raid = RaidManager.startPvp(level, v, attacker.getUUID(), attacker);
            check(raid != null && raid.pvp(), "no empezó el asedio entre jugadores");
            check(PvpSiege.defenders(level, raid).size() == 1, "defensores: " + PvpSiege.defenders(level, raid).size() + " (tenía que ser 1)");

            RaidManager.tick(level);
            check(RaidManager.get(level, center) != null, "con un defensor en pie se ganó el asedio");
            // El del barracón sale a defender (pedido de Ale: "como contra la IA")
            BarracksData.Barracks vb = BarracksData.get(level).at(villageCamp);
            check(vb == null || vb.soldiers.isEmpty(), "el soldado del barracón de la aldea no salió a defender");
            java.util.List<AbstractRecruitEntity> defending = PvpSiege.defenders(level, raid);
            check(defending.size() == 2, "defensores después del refuerzo: " + defending.size() + " (tenía que ser 2)");

            for (AbstractRecruitEntity s : defending) s.kill();
            RaidManager.tick(level);
            check(RaidManager.get(level, center) == null, "sin defensores el asedio no terminó");
            VillageData.Village now = data.at(center);
            check(now != null && attacker.getUUID().equals(now.owner()), "la aldea no pasó al atacante");
            CampRegistry reg = CampRegistry.get(level);
            check(reg.isCampOf(attacker.getUUID(), villageCamp) && !reg.campsOf(defender).contains(villageCamp),
                    "el Campamento de la aldea no pasó al atacante");
            check(BarracksData.get(level).at(villageCamp) == null, "quedó el barracón del que perdió en la aldea");
            check(attacker.getUUID().equals(worker.getOwnerUUID()), "el trabajador de la aldea sigue siendo del que la perdió");
            worker.discard();
        } finally {
            RaidManager.discard(level, center);
            Territory.remove(level, center);
            BarracksData.get(level).remove(villageCamp);
            BarracksData.get(level).remove(otherCamp);
            CampRegistry.get(level).unregister(defender, villageCamp);
            CampRegistry.get(level).unregister(defender, otherCamp);
            CampRegistry.get(level).unregister(attacker.getUUID(), villageCamp);
            data.remove(center);
        }
        h.succeed();
    }

    private static CampBlockEntity camp(GameTestHelper h, BlockPos abs, UUID owner) {
        h.getLevel().setBlockAndUpdate(abs, ModBlocks.CAMP.get().defaultBlockState());
        if (!(h.getLevel().getBlockEntity(abs) instanceof CampBlockEntity camp)) throw new GameTestAssertException("no se creó el Campamento");
        camp.setOwner(owner);
        return camp;
    }

    private static AbstractRecruitEntity soldier(GameTestHelper h, UUID owner, BlockPos rel) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse("recruits:recruit"));
        check(type != null, "Recruits no está cargado");
        Entity e = h.spawn(type, rel);
        check(e instanceof AbstractRecruitEntity, "recruits:recruit no es un AbstractRecruitEntity");
        AbstractRecruitEntity r = (AbstractRecruitEntity) e;
        for (EquipmentSlot slot : EquipmentSlot.values()) r.setItemSlot(slot, ItemStack.EMPTY);
        r.getInventory().clearContent();
        r.setOwnerUUID(Optional.of(owner));
        r.setIsOwned(true);
        return r;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
