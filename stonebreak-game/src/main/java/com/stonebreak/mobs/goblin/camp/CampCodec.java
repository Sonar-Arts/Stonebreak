package com.stonebreak.mobs.goblin.camp;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.GoblinLoadout;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A camp as text, to keep in its fire's state string: its roster (each goblin's loadout), where its
 * chest is, how long it still waits to rebuild after a breakage, whatever it holds outside that
 * chest, and the world tick its state stands at ({@link GoblinCamp#stampAt}), so a thawed camp
 * can live through its time away. Base64url, so nothing inside can clash with the state string's
 * {@code ;} and {@code =}. The hunt and hunger bookkeeping is not kept — a thawed camp starts with
 * a clean stomach.
 *
 * <p>Version 2 added the stamp ({@code t} line). Version 1 data has none and decodes as a camp
 * that owes no time; older readers skip the line.
 */
public final class CampCodec {

    private static final String VERSION = "2";

    private CampCodec() {
    }

    /** The camp with no clock to stamp it by: stamped only if it still owes time from a thaw. */
    public static String encode(GoblinCamp camp) {
        return encode(camp, -1L);
    }

    /** The camp as it stands at world tick {@code nowTicks} (negative: no clock). */
    public static String encode(GoblinCamp camp, long nowTicks) {
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
        if (camp.repairWait() > 0.0f) {
            sb.append("r ").append(Math.round(camp.repairWait())).append('\n');
        }
        long stamp = camp.stampAt(nowTicks);
        if (stamp >= 0) {
            sb.append("t ").append(stamp).append('\n');
        }
        for (ItemStack s : camp.pouch().slots()) { // a chest saves its own slots
            if (s != null && !s.isEmpty()) {
                sb.append("s ").append(s.getItem().getId()).append(' ').append(s.getCount()).append('\n');
            }
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A camp at {@code fire} with every member waiting to be brought back, owing the time since its
     * stamp; an unreadable one is empty.
     */
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
            } else if (line.startsWith("r ")) {
                try {
                    camp.setRepairWait(Float.parseFloat(line.substring(2).trim()));
                } catch (NumberFormatException ignored) {
                    // no wait: the camp may build straight away
                }
            } else if (line.startsWith("t ")) {
                try {
                    camp.setOfflineSince(Long.parseLong(line.substring(2).trim()));
                } catch (NumberFormatException ignored) {
                    // no stamp: the camp owes no time
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
