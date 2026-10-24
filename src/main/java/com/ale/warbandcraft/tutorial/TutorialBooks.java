package com.ale.warbandcraft.tutorial;

import com.ale.warbandcraft.WarbandCraft;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Libros del tutorial: {@code data/warbandcraft/warband_tutorial/NN_algo.txt}, en orden por nombre (el 00 va al atril
 * del medio, los demás a las paredes). Formato:
 * <pre>
 * titulo: El Campamento          (título del libro, máx. 32)
 * cartel: El|Campamento          (renglones del cartel, el número se pone solo)
 * texto...                       ("## " = subtítulo, renglón vacío = párrafo, "---" = página nueva)
 * </pre>
 * Las páginas se arman midiendo el ancho de las letras de la fuente de Minecraft (114 px, 14 renglones).
 */
public final class TutorialBooks {
    private static final int PAGE_WIDTH = 113;
    private static final int PAGE_LINES = 14;
    private static final int MAX_PAGES = 100;

    public record Topic(String id, String title, List<String> sign, List<Component> pages) {
        public ItemStack book() {
            ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
            CompoundTag tag = stack.getOrCreateTag();
            tag.putString("title", title);
            tag.putString("author", "Warband Craft");
            tag.putInt("generation", 0);
            tag.putBoolean("resolved", true);
            ListTag list = new ListTag();
            for (Component p : pages) list.add(StringTag.valueOf(Component.Serializer.toJson(p)));
            tag.put("pages", list);
            return stack;
        }
    }

    private TutorialBooks() {}

    public static List<Topic> load(MinecraftServer server) {
        Map<ResourceLocation, Resource> found = new TreeMap<>(server.getResourceManager()
                .listResources("warband_tutorial", rl -> rl.getNamespace().equals(WarbandCraft.MODID) && rl.getPath().endsWith(".txt")));
        List<Topic> out = new ArrayList<>();
        found.forEach((rl, res) -> {
            String path = rl.getPath();
            String id = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = res.openAsReader()) {
                String l;
                while ((l = r.readLine()) != null) lines.add(l.replace("﻿", ""));
            } catch (Exception e) {
                WarbandCraft.LOGGER.warn("Tutorial: no se pudo leer {}", rl, e);
                return;
            }
            out.add(parse(id, lines));
        });
        return out;
    }

    private static Topic parse(String id, List<String> lines) {
        String title = id;
        List<String> sign = new ArrayList<>();
        List<String> body = new ArrayList<>();
        for (String l : lines) {
            if (body.isEmpty() && l.startsWith("titulo:")) title = l.substring(7).trim();
            else if (body.isEmpty() && l.startsWith("cartel:")) sign = List.of(l.substring(7).trim().split("\\|"));
            else if (!body.isEmpty() || !l.isBlank()) body.add(l);
        }
        if (title.length() > 32) title = title.substring(0, 32);
        return new Topic(id, title, sign, paginate(body));
    }

    /** Un renglón ya cortado: texto y si es subtítulo. Null = página nueva. */
    private record Row(String text, boolean heading) {}

    private static List<Component> paginate(List<String> body) {
        List<Row> rows = new ArrayList<>();
        for (String l : body) {
            String t = l.stripTrailing();
            if (t.equals("---")) {
                rows.add(null);
            } else if (t.isBlank()) {
                rows.add(new Row("", false));
            } else if (t.startsWith("## ")) {
                for (String w : wrap(t.substring(3).trim(), true)) rows.add(new Row(w, true));
            } else {
                for (String w : wrap(t, false)) rows.add(new Row(w, false));
            }
        }
        List<List<Row>> pages = new ArrayList<>();
        List<Row> page = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row == null) {
                if (!page.isEmpty()) pages.add(page);
                page = new ArrayList<>();
                continue;
            }
            if (page.isEmpty() && row.text().isEmpty()) continue;   // la página no empieza con un renglón vacío
            boolean orphanHeading = row.heading() && page.size() >= PAGE_LINES - 1;   // un subtítulo no queda solo abajo
            if (page.size() >= PAGE_LINES || orphanHeading) {
                pages.add(page);
                page = new ArrayList<>();
                if (row.text().isEmpty()) continue;
            }
            page.add(row);
        }
        if (!page.isEmpty()) pages.add(page);

        List<Component> out = new ArrayList<>();
        for (List<Row> p : pages) {
            if (out.size() >= MAX_PAGES) break;
            MutableComponent c = Component.empty();
            for (int i = 0; i < p.size(); i++) {
                Row r = p.get(i);
                String text = r.text() + (i < p.size() - 1 ? "\n" : "");
                c.append(r.heading() ? Component.literal(text).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
                        : Component.literal(text));
            }
            out.add(c);
        }
        if (out.isEmpty()) out.add(Component.empty());
        return out;
    }

    private static List<String> wrap(String text, boolean bold) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int width = 0;
        int space = width(' ', bold);
        for (String word : text.split(" ")) {
            int w = 0;
            for (char ch : word.toCharArray()) w += width(ch, bold);
            if (line.length() > 0 && width + space + w > PAGE_WIDTH) {
                out.add(line.toString());
                line.setLength(0);
                width = 0;
            }
            if (line.length() > 0) {
                line.append(' ');
                width += space;
            }
            line.append(word);
            width += w;
        }
        out.add(line.toString());
        return out;
    }

    /** Ancho en píxeles de una letra de la fuente de Minecraft (con el espacio de después); negrita = +1. */
    private static int width(char ch, boolean bold) {
        char c = switch (ch) {
            case 'á', 'à' -> 'a';
            case 'é' -> 'e';
            case 'í' -> 'i';
            case 'ó' -> 'o';
            case 'ú', 'ü' -> 'u';
            case 'ñ' -> 'n';
            case 'Á' -> 'A';
            case 'É' -> 'E';
            case 'Í' -> 'I';
            case 'Ó' -> 'O';
            case 'Ú' -> 'U';
            case 'Ñ' -> 'N';
            default -> ch;
        };
        int w = switch (c) {
            case 'i', '!', ',', '.', ':', ';', '|', '\'', '¡' -> 2;
            case 'l', '`' -> 3;
            case ' ', 't', 'I', '[', ']', '(', ')', '{', '}', '"', '*' -> 4;
            case 'f', 'k', '<', '>', '¿' -> 5;
            case '@', '~', '«', '»' -> 7;
            case '→', '←', '▶', '◀', '▲', '▼', '·', '×' -> 8;
            default -> 6;
        };
        return w + (bold ? 1 : 0);
    }
}
