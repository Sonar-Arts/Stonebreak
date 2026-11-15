package com.stonebreak.mobs.goblin.camp;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.GoblinLoadout;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A camp as text, to keep in its fire's state string: its roster (each goblin's loadout), where its
 * chest is, and whatever it holds outside that chest. Base64url, so nothing inside can clash with the state string's {@code ;} and {@code =}.
 * The hunt and hunger bookkeeping is not kept — a thawed camp starts with a clean stomach.
 */
public final class CampCodec {

    private static final String VERSION = "1";

    private CampCodec() {
    }

    public static String encode(GoblinCamp camp) {
        StringBuilder sb = new StringBuilder(VERSION).append('\n');
        for (Goblin g : camp.members()) {
            sb.append("m ").append(g.getLoadout().encode()).append('\n');
        }
        for (GoblinLoadout waiting : camp.waiting()) {
            sb.append("m ").append(waiting.encode()).append('\n');
        }
        if (camp.chest() != null) {
            sb.append("c ").append(camp.chest().x()).append(' ').append(camp.chest().y()).append(' ')
                    .append(camp.chest().z()).append('\n');
        }
        for (ItemStack s : camp.pouch().slots()) { // a chest saves its own slots
            if (s != null && !s.isEmpty()) {
                sb.append("s ").append(s.getItem().getId()).append(' ').append(s.getCount()).append('\n');
            }
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A camp at {@code fire} with every member waiting to be brought back; an unreadable one is empty. */
    public static GoblinCamp decode(BlockPos fire, String data) {
        GoblinCamp camp = new GoblinCamp(fire);
        String text;
        try {
            text = new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return camp;
        }
        for (String line : text.split("\n")) {
            if (line.startsWith("m ")) {
                camp.addWaiting(GoblinLoadout.decode(line.substring(2)));
            } else if (line.startsWith("c ")) {
                String[] p = line.substring(2).split(" ");
                try {
                    camp.setChest(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
                } catch (RuntimeException ignored) {
                    // no chest: the camp builds a new one
                }
            } else if (line.startsWith("s ")) {
                String[] parts = line.substring(2).split(" ");
                try {
                    ItemStack stack = new ItemStack(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                    if (!stack.isEmpty()) {
                        camp.pouch().add(stack);
                    }
                } catch (RuntimeException ignored) {
                    // a damaged line loses that stack, not the camp
                }
            }
        }
        return camp;
    }
}
