package com.stonebreak.player.combat.dodge;

import com.stonebreak.player.Player;

/**
 * Notified when a player successfully completes a dodge. The upcoming Rogue class
 * subscribes to this to stack its Momentum passive; the event is fired (and this
 * interface kept in place) even before any subscriber exists.
 */
@FunctionalInterface
public interface DodgeListener {
    void onDodgeSuccess(Player player);
}
