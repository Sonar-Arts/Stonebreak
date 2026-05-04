package com.stonebreak.player.combat;

import static com.stonebreak.player.PlayerConstants.STAMINA_DRAIN_RATE;
import static com.stonebreak.player.PlayerConstants.STAMINA_REGEN_RATE;

/**
 * Tracks player stamina. Drains while sprinting, regenerates otherwise.
 * Max stamina is driven by DEX score via Player.updateDerivedStats().
 */
public class StaminaController {

    private float stamina;
    private float maxStamina;
    private boolean sprinting;

    public StaminaController(float maxStamina) {
        this.maxStamina = maxStamina;
        this.stamina = maxStamina;
    }

    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
    }

    public void update(float dt) {
        if (sprinting) {
            stamina = Math.max(0, stamina - STAMINA_DRAIN_RATE * dt);
        } else {
            stamina = Math.min(maxStamina, stamina + STAMINA_REGEN_RATE * dt);
        }
    }

    public boolean hasStamina() { return stamina > 0; }
    public float getStamina()   { return stamina; }
    public float getMaxStamina() { return maxStamina; }

    public void setMaxStamina(float newMax) {
        float diff = newMax - this.maxStamina;
        this.maxStamina = newMax;
        if (diff > 0) stamina = Math.min(maxStamina, stamina + diff);
        else          stamina = Math.min(stamina, maxStamina);
    }
}
