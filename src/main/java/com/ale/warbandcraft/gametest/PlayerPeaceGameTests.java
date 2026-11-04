package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.faction.PlayerPeace;
import com.mojang.authlib.GameProfile;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsDiplomacyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Pedido de Ale (4/11): la paz entre jugadores. Uno la propone, el otro le pone precio, el primero acepta: quedan NEUTRAL en
 * la Diplomacia de Recruits y con tregua.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class PlayerPeaceGameTests {
    private static final String TEMPLATE = "empty";

    @GameTest(template = TEMPLATE)
    public static void playersSignPeace(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        RecruitsDiplomacyManager d = FactionEvents.recruitsDiplomacyManager;
        check(d != null, "no está la Diplomacia de Recruits");
        String tag = Integer.toHexString(h.absolutePos(BlockPos.ZERO).hashCode());
        String a = "wbpa" + tag, b = "wbpb" + tag;
        FakePlayer pa = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(a.getBytes()), "peace_a"));
        PlayerPeace pp = PlayerPeace.get(level.getServer());
        try {
            d.setRelation(a, b, RecruitsDiplomacyManager.DiplomacyStatus.ENEMY, level);
            check(PlayerPeace.atWar(a, b), "no quedaron en guerra");
            PlayerPeace.propose(level, pa, a, b);
            check(pp.offer(a, b) != null, "no quedó la propuesta");
            check(pp.toPrice(level, b).size() == 1, "la otra facción no ve la propuesta para ponerle precio");
            PlayerPeace.setPrice(level, null, a, b, 0);
            check(pp.toAccept(level, a).size() == 1, "el que propuso no ve el precio");
            PlayerPeace.accept(level, pa, a, b);
            check(pp.offer(a, b) == null, "quedó la propuesta después de firmar");
            check(pp.truce(level, a, b), "no hay tregua");
            check(!PlayerPeace.atWar(a, b), "siguen en guerra en Recruits");

            // Rechazar: otra vez en guerra, propuesta y la rechaza el que recibe
            pp.forget(a, b);
            d.setRelation(a, b, RecruitsDiplomacyManager.DiplomacyStatus.ENEMY, level);
            PlayerPeace.propose(level, pa, a, b);
            PlayerPeace.reject(level, a, b, b);
            check(pp.offer(a, b) == null && !pp.truce(level, a, b), "rechazada y quedó algo");
        } finally {
            pp.forget(a, b);
            d.setRelation(a, b, RecruitsDiplomacyManager.DiplomacyStatus.NEUTRAL, level);
            d.setRelation(b, a, RecruitsDiplomacyManager.DiplomacyStatus.NEUTRAL, level);
        }
        h.succeed();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
