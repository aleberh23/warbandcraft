package com.ale.warbandcraft.network;

import com.ale.warbandcraft.WarbandCraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Canal de red del mod: pantallas que reemplazan mensajes de chat (relaciones, elegir qué hacer con una
 * aldea ganada), las órdenes de la vista aérea (H5) y las pestañas del Campamento y el Zapador (H6) y el barracón (H8).
 */
public final class ModNetworking {
    private static final String PROTOCOL_VERSION = "10";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(WarbandCraft.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int nextId = 0;

    private ModNetworking() {}

    public static void register() {
        CHANNEL.registerMessage(nextId++, OpenRaidChoiceMessage.class,
                OpenRaidChoiceMessage::encode, OpenRaidChoiceMessage::decode, OpenRaidChoiceMessage::handle);
        CHANNEL.registerMessage(nextId++, RaidChoiceMessage.class,
                RaidChoiceMessage::encode, RaidChoiceMessage::decode, RaidChoiceMessage::handle);
        CHANNEL.registerMessage(nextId++, OpenRelationsMessage.class,
                OpenRelationsMessage::encode, OpenRelationsMessage::decode, OpenRelationsMessage::handle);
        CHANNEL.registerMessage(nextId++, AerialOrderMessage.class,
                AerialOrderMessage::encode, AerialOrderMessage::decode, AerialOrderMessage::handle);
        CHANNEL.registerMessage(nextId++, AerialSetStateMessage.class,
                AerialSetStateMessage::encode, AerialSetStateMessage::decode, AerialSetStateMessage::handle);
        CHANNEL.registerMessage(nextId++, AerialInspectMessage.class,
                AerialInspectMessage::encode, AerialInspectMessage::decode, AerialInspectMessage::handle);
        // H6: pestañas del Campamento y Zapador
        CHANNEL.registerMessage(nextId++, CampTabMessage.class,
                CampTabMessage::encode, CampTabMessage::decode, CampTabMessage::handle);
        CHANNEL.registerMessage(nextId++, OpenDoctrineMessage.class,
                OpenDoctrineMessage::encode, OpenDoctrineMessage::decode, OpenDoctrineMessage::handle);
        CHANNEL.registerMessage(nextId++, ChooseDoctrineMessage.class,
                ChooseDoctrineMessage::encode, ChooseDoctrineMessage::decode, ChooseDoctrineMessage::handle);
        CHANNEL.registerMessage(nextId++, RenameClassMessage.class,
                RenameClassMessage::encode, RenameClassMessage::decode, RenameClassMessage::handle);
        CHANNEL.registerMessage(nextId++, SapperOrderMessage.class,
                SapperOrderMessage::encode, SapperOrderMessage::decode, SapperOrderMessage::handle);
        // H8: barracones y nombre del Campamento
        CHANNEL.registerMessage(nextId++, OpenBarracksMessage.class,
                OpenBarracksMessage::encode, OpenBarracksMessage::decode, OpenBarracksMessage::handle);
        CHANNEL.registerMessage(nextId++, BarracksActionMessage.class,
                BarracksActionMessage::encode, BarracksActionMessage::decode, BarracksActionMessage::handle);
        CHANNEL.registerMessage(nextId++, RenameCampMessage.class,
                RenameCampMessage::encode, RenameCampMessage::decode, RenameCampMessage::handle);
        CHANNEL.registerMessage(nextId++, AerialCampsMessage.class,
                AerialCampsMessage::encode, AerialCampsMessage::decode, AerialCampsMessage::handle);
        CHANNEL.registerMessage(nextId++, AerialCampsMessage.Request.class,
                AerialCampsMessage.Request::encode, AerialCampsMessage.Request::decode, AerialCampsMessage.Request::handle);
        CHANNEL.registerMessage(nextId++, ToBarracksMessage.class,
                ToBarracksMessage::encode, ToBarracksMessage::decode, ToBarracksMessage::handle);
        // H13: rutas, viaje y envíos
        CHANNEL.registerMessage(nextId++, OpenRoutesMessage.class,
                OpenRoutesMessage::encode, OpenRoutesMessage::decode, OpenRoutesMessage::handle);
        CHANNEL.registerMessage(nextId++, RouteActionMessage.class,
                RouteActionMessage::encode, RouteActionMessage::decode, RouteActionMessage::handle);
        CHANNEL.registerMessage(nextId++, TravelStateMessage.class,
                TravelStateMessage::encode, TravelStateMessage::decode, TravelStateMessage::handle);
        CHANNEL.registerMessage(nextId++, SendShipmentMessage.class,
                SendShipmentMessage::encode, SendShipmentMessage::decode, SendShipmentMessage::handle);
        // H9: orden Batalla
        CHANNEL.registerMessage(nextId++, BattleOrderMessage.class,
                BattleOrderMessage::encode, BattleOrderMessage::decode, BattleOrderMessage::handle);
        // Tus puntos en el mapa de Recruits
        CHANNEL.registerMessage(nextId++, MapPointsMessage.class,
                MapPointsMessage::encode, MapPointsMessage::decode, MapPointsMessage::handle);
        CHANNEL.registerMessage(nextId++, MapPointsMessage.Request.class,
                MapPointsMessage.Request::encode, MapPointsMessage.Request::decode, MapPointsMessage.Request::handle);
        // H10: economía
        CHANNEL.registerMessage(nextId++, WorkerZoneMessage.class,
                WorkerZoneMessage::encode, WorkerZoneMessage::decode, WorkerZoneMessage::handle);
        // H11: panel de control
        CHANNEL.registerMessage(nextId++, OpenPanelMessage.class,
                OpenPanelMessage::encode, OpenPanelMessage::decode, OpenPanelMessage::handle);
        CHANNEL.registerMessage(nextId++, PanelActionMessage.class,
                PanelActionMessage::encode, PanelActionMessage::decode, PanelActionMessage::handle);
        // H16: gestión de aldeas
        CHANNEL.registerMessage(nextId++, ManageMessage.class,
                ManageMessage::encode, ManageMessage::decode, ManageMessage::handle);
        CHANNEL.registerMessage(nextId++, ManageActionMessage.class,
                ManageActionMessage::encode, ManageActionMessage::decode, ManageActionMessage::handle);
        // Cartelito de la aldea en que estás
        CHANNEL.registerMessage(nextId++, VillageHudMessage.class,
                VillageHudMessage::encode, VillageHudMessage::decode, VillageHudMessage::handle);
        // H22: vista previa de un edificio en el modo Gestión
        CHANNEL.registerMessage(nextId++, BuildPreviewMessage.class,
                BuildPreviewMessage::encode, BuildPreviewMessage::decode, BuildPreviewMessage::handle);
        // Fundar una aldea desde el Campamento
        CHANNEL.registerMessage(nextId++, FoundVillageMessage.class,
                FoundVillageMessage::encode, FoundVillageMessage::decode, FoundVillageMessage::handle);
        // H20: ejércitos de campaña
        CHANNEL.registerMessage(nextId++, OpenArmiesMessage.class,
                OpenArmiesMessage::encode, OpenArmiesMessage::decode, OpenArmiesMessage::handle);
        CHANNEL.registerMessage(nextId++, ArmyActionMessage.class,
                ArmyActionMessage::encode, ArmyActionMessage::decode, ArmyActionMessage::handle);
        CHANNEL.registerMessage(nextId++, ArmyStatusMessage.class,
                ArmyStatusMessage::encode, ArmyStatusMessage::decode, ArmyStatusMessage::handle);
        // H23: meterse en batallas, asediar con la tecla B
        CHANNEL.registerMessage(nextId++, ChoiceMessage.class,
                ChoiceMessage::encode, ChoiceMessage::decode, ChoiceMessage::handle);
        CHANNEL.registerMessage(nextId++, ChoiceAnswerMessage.class,
                ChoiceAnswerMessage::encode, ChoiceAnswerMessage::decode, ChoiceAnswerMessage::handle);
        CHANNEL.registerMessage(nextId++, BattleKeyMessage.class,
                BattleKeyMessage::encode, BattleKeyMessage::decode, BattleKeyMessage::handle);
        // H24: el mercado
        CHANNEL.registerMessage(nextId++, OpenMarketMessage.class,
                OpenMarketMessage::encode, OpenMarketMessage::decode, OpenMarketMessage::handle);
        CHANNEL.registerMessage(nextId++, MarketActionMessage.class,
                MarketActionMessage::encode, MarketActionMessage::decode, MarketActionMessage::handle);
    }
}
