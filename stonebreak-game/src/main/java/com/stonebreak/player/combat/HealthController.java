package com.stonebreak.player.combat;

import static com.stonebreak.player.PlayerConstants.HEALTH_PER_HEART;
import static com.stonebreak.player.PlayerConstants.MAX_HEALTH;
import static com.stonebreak.player.PlayerConstants.SPAWN_PROTECTION_DURATION;

/**
 * Owns player health, death flag, and spawn-protection window. Damage requests from
 * other subsystems (fall damage, combat) funnel through damage(); death is a terminal
 * state cleared only by {@link DeathHandler#respawn()}.
 */
public class HealthController {

    private float health;
    private float maxHealth;
    private boolean dead;

    private boolean spawnProtection;
    private float spawnProtectionTimer;

    public HealthController() {
        this.maxHealth = MAX_HEALTH;
        this.health = maxHealth;
        this.dead = false;
        this.spawnProtection = true;
        this.spawnProtectionTimer = 0.0f;
    }

    public void updateSpawnProtection(float deltaTime, boolean onGround) {
        if (!spawnProtection) return;
        spawnProtectionTimer += deltaTime;
        if (spawnProtectionTimer >= SPAWN_PROTECTION_DURATION) {
            spawnProtection = false;
            System.out.println("Spawn protection ended - fall damage now active");
        }
        if (onGround && spawnProtectionTimer > 0.5f) {
            spawnProtection = false;
            System.out.println("Spawn protection ended early - player safely on ground");
        }
    }

    public void damage(float amount) {
        if (dead) return;
        System.out.println("DEBUG: Player took " + amount + " damage. Health: " + health + " -> " + (health - amount));
        health -= amount;
        if (health <= 0) {
            health = 0;
            System.out.println("DEBUG: Health <= 0, triggering death");
            dead = true;
        }
    }

    public void heal(float amount) {
        if (dead) return;
        health = Math.min(health + amount, maxHealth);
    }

    public float getHealth() { return health; }
    public float getMaxHealth() { return maxHealth; }
    public boolean isDead() { return dead; }
    public int getHearts() { return (int) Math.ceil(health / HEALTH_PER_HEART); }

    public void setHealth(float health) { this.health = health; }

    public void enableSpawnProtection() {
        this.spawnProtection = true;
        this.spawnProtectionTimer = 0.0f;
    }

    public boolean hasSpawnProtection() { return spawnProtection; }

    public void restoreFullHealth() {
        this.health = maxHealth;
        this.dead = false;
    }

    /** Directly set death flag (used by DeathHandler after running death side effects). */
    public void markDead() { this.dead = true; }
}
