package com.ale.warbandcraft.market;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * H24: lo que ve el dueño en la pantalla Mercado (pestañas Ventas · Envíos · Lonja). Ítems por id ("minecraft:bread").
 *
 * @param merchants  mercaderes tuyos de la aldea (y cuántos admite el nivel)
 * @param rows       lo que hay en el almacén (y lo que tiene una regla aunque no haya)
 * @param sales      las últimas ventas, ya escritas
 * @param dests      adónde se puede mandar (por tus rutas), con el tiempo de viaje
 * @param stores     Lonja (nivel 3): los otros almacenes conectados y cuánto se puede pedir de cada cosa
 */
public record MarketView(BlockPos center, Component name, int level, int merchants, int maxMerchants, int maxOffers,
                         int maxSchedules, List<SaleRow> rows, List<Component> sales, List<ScheduleRow> schedules,
                         List<Dest> dests, List<StoreRow> stores) {

    /** Un ítem del almacén: cuántos hay, mínimo, lote, precio por lote, si se vende. */
    public record SaleRow(String item, int count, int min, int lot, int price, boolean sell) {}

    public record LineRow(String item, int amount, boolean refill) {}

    /** Un envío programado: destino (null = sin elegir), cada cuántos días, en cuántos sale el próximo. */
    public record ScheduleRow(UUID id, @Nullable BlockPos to, Component toName, int every, int inDays, boolean paused,
                              List<LineRow> lines) {}

    public record Dest(BlockPos pos, Component name, int ticks) {}

    /** Un almacén para la Lonja: {@code live} = cuenta de ahora (si no, la última que se vio). */
    public record StoreRow(BlockPos pos, Component name, boolean live, List<ItemCount> items) {}

    /** {@code available} = lo que se puede pedir (lo que hay menos el mínimo de ese lugar). */
    public record ItemCount(String item, int available) {}

    public static void write(FriendlyByteBuf buf, MarketView v) {
        buf.writeBlockPos(v.center);
        buf.writeComponent(v.name);
        buf.writeVarInt(v.level);
        buf.writeVarInt(v.merchants);
        buf.writeVarInt(v.maxMerchants);
        buf.writeVarInt(v.maxOffers);
        buf.writeVarInt(v.maxSchedules);
        buf.writeCollection(v.rows, (b, r) -> {
            b.writeUtf(r.item);
            b.writeVarInt(r.count);
            b.writeVarInt(r.min);
            b.writeVarInt(r.lot);
            b.writeVarInt(r.price);
            b.writeBoolean(r.sell);
        });
        buf.writeCollection(v.sales, FriendlyByteBuf::writeComponent);
        buf.writeCollection(v.schedules, (b, s) -> {
            b.writeUUID(s.id);
            b.writeNullable(s.to, FriendlyByteBuf::writeBlockPos);
            b.writeComponent(s.toName);
            b.writeVarInt(s.every);
            b.writeVarInt(s.inDays);
            b.writeBoolean(s.paused);
            b.writeCollection(s.lines, (b2, l) -> {
                b2.writeUtf(l.item);
                b2.writeVarInt(l.amount);
                b2.writeBoolean(l.refill);
            });
        });
        buf.writeCollection(v.dests, (b, d) -> {
            b.writeBlockPos(d.pos);
            b.writeComponent(d.name);
            b.writeVarInt(d.ticks);
        });
        buf.writeCollection(v.stores, (b, s) -> {
            b.writeBlockPos(s.pos);
            b.writeComponent(s.name);
            b.writeBoolean(s.live);
            b.writeCollection(s.items, (b2, i) -> {
                b2.writeUtf(i.item);
                b2.writeVarInt(i.available);
            });
        });
    }

    public static MarketView read(FriendlyByteBuf buf) {
        BlockPos center = buf.readBlockPos();
        Component name = buf.readComponent();
        int level = buf.readVarInt(), merchants = buf.readVarInt(), maxMerchants = buf.readVarInt(), maxOffers = buf.readVarInt(),
                maxSchedules = buf.readVarInt();
        List<SaleRow> rows = buf.readCollection(ArrayList::new, b -> new SaleRow(b.readUtf(), b.readVarInt(), b.readVarInt(),
                b.readVarInt(), b.readVarInt(), b.readBoolean()));
        List<Component> sales = buf.readCollection(ArrayList::new, FriendlyByteBuf::readComponent);
        List<ScheduleRow> schedules = buf.readCollection(ArrayList::new, b -> new ScheduleRow(b.readUUID(),
                b.readNullable(FriendlyByteBuf::readBlockPos), b.readComponent(), b.readVarInt(), b.readVarInt(), b.readBoolean(),
                b.readCollection(ArrayList::new, b2 -> new LineRow(b2.readUtf(), b2.readVarInt(), b2.readBoolean()))));
        List<Dest> dests = buf.readCollection(ArrayList::new, b -> new Dest(b.readBlockPos(), b.readComponent(), b.readVarInt()));
        List<StoreRow> stores = buf.readCollection(ArrayList::new, b -> new StoreRow(b.readBlockPos(), b.readComponent(), b.readBoolean(),
                b.readCollection(ArrayList::new, b2 -> new ItemCount(b2.readUtf(), b2.readVarInt()))));
        return new MarketView(center, name, level, merchants, maxMerchants, maxOffers, maxSchedules, rows, sales, schedules, dests, stores);
    }
}
