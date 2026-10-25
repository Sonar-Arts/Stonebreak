package com.stonebreak.mobs.goblin;

import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.combat.MobWeapon;
import com.stonebreak.mobs.sbe.AttachmentVisual;
import com.stonebreak.mobs.sbe.Clothing;
import com.stonebreak.mobs.sbe.EntityAttachments;

import java.util.Random;

/**
 * What one goblin carries and wears. Rolled once at spawn, then saved and replicated as a single
 * compact string, so a goblin looks the same after a reload and to every player.
 *
 * <p>Looks are attachments on the goblin's sockets — the same system as player hats — rather than
 * separate models per variant.
 *
 * @param bandanaColor one of {@link Clothing#BANDANA_COLORS} when {@code headwear} is BANDANA, else null
 */
public record GoblinLoadout(MobWeapon weapon, Headwear headwear, String bandanaColor, boolean earring) {

    /** What sits on the head socket; one at a time, since they share it. */
    public enum Headwear { NONE, BANDANA, PIRATE_HAT }

    // Roll odds. Weapon weights are relative; the rest are chances out of 1.
    private static final int DAGGER_WEIGHT = 45;
    private static final int SMACKER_WEIGHT = 30;
    private static final int BOW_WEIGHT = 25;
    static final float PIRATE_HAT_CHANCE = 0.03f;
    static final float BANDANA_CHANCE = 0.40f;
    static final float EARRING_CHANCE = 0.25f;

    private static final String SEPARATOR = ";";
    private static final String COLOR_SEPARATOR = ":";
    private static final String EARRING_TOKEN = "EARRING";

    public GoblinLoadout {
        if (weapon == null) weapon = MobWeapon.DAGGER;
        if (headwear == null) headwear = Headwear.NONE;
        if (headwear != Headwear.BANDANA) {
            bandanaColor = null;
        } else if (!Clothing.BANDANA_COLORS.contains(bandanaColor)) {
            bandanaColor = Clothing.BANDANA_COLORS.getFirst();
        }
    }

    /** A fresh random goblin. The rare pirate hat wins the head over a bandana. */
    public static GoblinLoadout roll(Random random) {
        MobWeapon weapon = rollWeapon(random);
        Headwear headwear = Headwear.NONE;
        String color = null;
        if (random.nextFloat() < PIRATE_HAT_CHANCE) {
            headwear = Headwear.PIRATE_HAT;
        } else if (random.nextFloat() < BANDANA_CHANCE) {
            headwear = Headwear.BANDANA;
            color = Clothing.BANDANA_COLORS.get(random.nextInt(Clothing.BANDANA_COLORS.size()));
        }
        return new GoblinLoadout(weapon, headwear, color, random.nextFloat() < EARRING_CHANCE);
    }

    private static MobWeapon rollWeapon(Random random) {
        int roll = random.nextInt(DAGGER_WEIGHT + SMACKER_WEIGHT + BOW_WEIGHT);
        if (roll < DAGGER_WEIGHT) return MobWeapon.DAGGER;
        if (roll < DAGGER_WEIGHT + SMACKER_WEIGHT) return MobWeapon.PATTY_SMACKER;
        return MobWeapon.BOW;
    }

    /** e.g. {@code BOW;BANDANA:Red;EARRING}. Used for both saves and spawn metadata. */
    public String encode() {
        StringBuilder out = new StringBuilder(weapon.name()).append(SEPARATOR).append(headwear.name());
        if (bandanaColor != null) {
            out.append(COLOR_SEPARATOR).append(bandanaColor);
        }
        if (earring) {
            out.append(SEPARATOR).append(EARRING_TOKEN);
        }
        return out.toString();
    }

    /** Inverse of {@link #encode()}; anything unreadable falls back to a plain dagger goblin. */
    public static GoblinLoadout decode(String encoded) {
        MobWeapon weapon = MobWeapon.DAGGER;
        Headwear headwear = Headwear.NONE;
        String color = null;
        boolean earring = false;
        if (encoded != null && !encoded.isBlank()) {
            String[] tokens = encoded.split(SEPARATOR);
            weapon = parse(MobWeapon.class, tokens[0], MobWeapon.DAGGER);
            for (int i = 1; i < tokens.length; i++) {
                String token = tokens[i];
                if (token.equals(EARRING_TOKEN)) {
                    earring = true;
                    continue;
                }
                String[] head = token.split(COLOR_SEPARATOR, 2);
                headwear = parse(Headwear.class, head[0], Headwear.NONE);
                color = head.length > 1 ? head[1] : null;
            }
        }
        return new GoblinLoadout(weapon, headwear, color, earring);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Hangs the weapon and cosmetics on the goblin's sockets. */
    public void applyTo(Object entityKey) {
        ItemType item = weapon.itemType();
        if (item != null) {
            EntityAttachments.attach(entityKey, weapon.socket(), new AttachmentVisual.Item(item));
        }
        switch (headwear) {
            case NONE -> Clothing.wear(entityKey, Clothing.HAT_SOCKET, null, null);
            case BANDANA -> Clothing.wear(entityKey, Clothing.HAT_SOCKET, Clothing.BANDANA, bandanaColor);
            case PIRATE_HAT -> Clothing.wear(entityKey, Clothing.HAT_SOCKET, Clothing.PIRATE_HAT, null);
        }
        Clothing.wear(entityKey, Clothing.EARRING_SOCKET, earring ? Clothing.GOLD_EARRING : null, null);
    }
}
