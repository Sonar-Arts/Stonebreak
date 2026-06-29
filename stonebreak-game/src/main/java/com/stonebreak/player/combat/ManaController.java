package com.stonebreak.player.combat;

/**
 * Tracks player mana. Regenerates continuously at a rate driven by WIS score
 * via Player.updateDerivedStats(). Spent by Arcanist spell casts.
 */
public class ManaController {

    private float mana;
    private float maxMana;
    private float regenRate;

    public ManaController(float maxMana, float regenRate) {
        this.maxMana = maxMana;
        this.regenRate = regenRate;
        this.mana = maxMana;
    }

    public void update(float dt) {
        mana = Math.min(maxMana, mana + regenRate * dt);
    }

    public float getMana()    { return mana; }
    public float getMaxMana() { return maxMana; }

    /** Deducts the cost if affordable. Returns false (and deducts nothing) otherwise. */
    public boolean trySpend(float cost) {
        if (mana < cost) return false;
        mana -= cost;
        return true;
    }

    /** Returns previously spent mana (e.g. when a cast fails after payment), clamped to max. */
    public void refund(float amount) {
        mana = Math.min(maxMana, mana + amount);
    }

    public void setMaxMana(float newMax) {
        float diff = newMax - this.maxMana;
        this.maxMana = newMax;
        if (diff > 0) mana = Math.min(maxMana, mana + diff);
        else          mana = Math.min(mana, maxMana);
    }

    public void setRegenRate(float regenRate) {
        this.regenRate = regenRate;
    }
}
