package com.stonebreak.mobs.sheep;

import org.joml.Vector3f;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.AnimationController;

public class Sheep extends LivingEntity {

    private boolean isGrazing;

    private final SheepAI sheepAI;
    private final String textureVariant;
    private final AnimationController animationController;

    public Sheep(World world, Vector3f position) {
        this(world, position, "default");
    }

    public Sheep(World world, Vector3f position, String textureVariant) {
        super(world, position, EntityType.SHEEP);
        this.textureVariant = textureVariant != null ? textureVariant : "default";
        this.isGrazing = false;
        this.sheepAI = new SheepAI(this);
        this.animationController = new AnimationController(this);
        this.interactionRange = 2.5f;
        this.turnSpeed = 200.0f;
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        if (sheepAI != null) sheepAI.update(deltaTime);
        animationController.updateAnimations(deltaTime);
    }

    @Override
    public void render(Renderer renderer) {
        // Handled by EntityRenderer
    }

    @Override
    public EntityType getType() {
        return EntityType.SHEEP;
    }

    @Override
    public void onInteract(Player player) {
        if (!isAlive()) return;
    }

    @Override
    public void onDamage(float damage, DamageSource source) {
        if (source == DamageSource.PLAYER) {
            Player player = Game.getPlayer();
            if (player != null) {
                Vector3f knockbackDir = new Vector3f(position).sub(player.getPosition());
                knockbackDir.y = 0;
                if (knockbackDir.length() > 0.01f) {
                    knockbackDir.normalize();
                    velocity.x += knockbackDir.x * 6.0f;
                    velocity.z += knockbackDir.z * 6.0f;
                    velocity.y += 0.6f;
                }
            }
        }
        if (sheepAI != null) sheepAI.onDamaged(damage);
    }

    @Override
    protected void onDeath() {
        if (sheepAI != null) sheepAI.cleanup();
    }

    @Override
    public ItemStack[] getDrops() {
        return new ItemStack[0];
    }

    @Override
    public int getXpReward() { return 4; }

    public void setGrazing(boolean grazing) {
        this.isGrazing = grazing;
    }

    public void startIdling() {
        this.velocity.set(0, velocity.y, 0);
    }

    public void jump() {
        if (isOnGround()) {
            Vector3f velocity = getVelocity();
            velocity.y = 8.5f;
            setVelocity(velocity);
            setOnGround(false);
        }
    }

    public SheepAI getAI() { return sheepAI; }
    public AnimationController getAnimationController() { return animationController; }
    public String getTextureVariant() { return textureVariant; }
}
