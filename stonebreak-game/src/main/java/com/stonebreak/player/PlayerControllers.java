package com.stonebreak.player;

import com.stonebreak.items.Inventory;
import com.stonebreak.player.combat.AttackController;
import com.stonebreak.player.combat.BowController;
import com.stonebreak.player.combat.DeathHandler;
import com.stonebreak.player.combat.FallDamageHandler;
import com.stonebreak.player.combat.HealthController;
import com.stonebreak.player.combat.ManaController;
import com.stonebreak.player.combat.StaminaController;
import com.stonebreak.player.combat.arcanist.ArcanistAbilityController;
import com.stonebreak.player.combat.berserker.BerserkerAbilityController;
import com.stonebreak.player.combat.dodge.DodgeController;
import com.stonebreak.player.combat.illusionist.IllusionistAbilityController;
import com.stonebreak.player.combat.ranger.RangerAbilityController;
import com.stonebreak.player.combat.rogue.RogueAbilityController;
import com.stonebreak.player.combat.stealth.StealthController;
import com.stonebreak.player.interaction.BlockBreaker;
import com.stonebreak.player.interaction.BlockPlacer;
import com.stonebreak.player.interaction.ItemDropInteraction;
import com.stonebreak.player.interaction.RaycastEngine;
import com.stonebreak.player.lifecycle.PlayerSpawnService;
import com.stonebreak.player.locomotion.FlightController;
import com.stonebreak.player.locomotion.JumpHandler;
import com.stonebreak.player.locomotion.SpectatorController;
import com.stonebreak.player.locomotion.SwimmingController;
import com.stonebreak.player.physics.CollisionHandler;
import com.stonebreak.player.physics.GroundChecker;
import com.stonebreak.player.physics.MovementController;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.World;

/**
 * Constructs and wires the player's controller suite (physics, locomotion, combat,
 * interaction, lifecycle) in dependency order. Pure construction: holds the wired
 * instances for {@link Player} (facade accessors) and {@link PlayerUpdatePipeline}
 * (per-tick sequencing) and owns no tick logic of its own.
 */
final class PlayerControllers {

    // Physics
    final CollisionHandler collisionHandler;
    final GroundChecker groundChecker;
    final MovementController movement;

    // Locomotion
    final SwimmingController swimming;
    final FlightController flight;
    final JumpHandler jumpHandler;
    final SpectatorController spectator;

    // Combat
    final AttackController attack;
    final BowController bow;
    final HealthController health;
    final StaminaController stamina;
    final ManaController mana;
    final FallDamageHandler fallDamage;
    final DeathHandler deathHandler;
    final BerserkerAbilityController berserkerAbilities;
    final RangerAbilityController rangerAbilities;
    final ArcanistAbilityController arcanistAbilities;
    final IllusionistAbilityController illusionistAbilities;
    final RogueAbilityController rogueAbilities;
    final DodgeController dodge;
    final StealthController stealth = new StealthController();

    // Interaction
    final RaycastEngine raycastEngine;
    final BlockBreaker blockBreaker;
    final BlockPlacer blockPlacer;
    final ItemDropInteraction itemDropInteraction;

    // Lifecycle
    final PlayerSpawnService spawnService;

    // RPG
    final CharacterStats characterStats;

    PlayerControllers(Player player, World world, PhysicsState state, Camera camera, Inventory inventory) {
        IBlockPlacementService blockPlacementService = new BlockPlacementValidator(world);

        this.collisionHandler = new CollisionHandler(state, world);
        this.groundChecker = new GroundChecker(state, collisionHandler);
        this.swimming = new SwimmingController(state, world);
        this.flight = new FlightController(state);
        this.jumpHandler = new JumpHandler(state);

        this.attack = new AttackController();
        this.bow = new BowController();
        this.health = new HealthController();
        this.stamina = new StaminaController(0);
        this.mana = new ManaController(0, 0);
        this.spectator = new SpectatorController(state, flight, health);
        this.movement = new MovementController(state, camera, collisionHandler, flight, swimming, jumpHandler, spectator);
        this.fallDamage = new FallDamageHandler(state, health);
        this.deathHandler = new DeathHandler(state, health, inventory, camera, world);
        this.berserkerAbilities = new BerserkerAbilityController();
        this.rangerAbilities = new RangerAbilityController();
        this.arcanistAbilities = new ArcanistAbilityController();
        this.illusionistAbilities = new IllusionistAbilityController();
        this.rogueAbilities = new RogueAbilityController();
        this.dodge = new DodgeController();
        // Momentum passive: a successful dodge grants the Rogue a stack (self-gated on class).
        this.dodge.addDodgeListener(rogueAbilities::onDodgeSuccess);

        this.raycastEngine = new RaycastEngine(state, camera, world);
        this.blockBreaker = new BlockBreaker(raycastEngine, inventory, attack, world);
        this.blockPlacer = new BlockPlacer(state, raycastEngine, inventory, blockPlacementService, world);
        this.itemDropInteraction = new ItemDropInteraction(state, camera, blockPlacementService, world);

        this.characterStats = new CharacterStats(player);
        this.spawnService = new PlayerSpawnService(state, camera, inventory, health, attack,
                blockBreaker, flight, jumpHandler, swimming, characterStats);
    }

    /** Re-points every world-bound controller at {@code world} and resets world-referencing ability state. */
    void setWorld(World world) {
        collisionHandler.setWorld(world);
        swimming.setWorld(world);
        raycastEngine.setWorld(world);
        blockBreaker.setWorld(world);
        blockPlacer.setWorld(world);
        itemDropInteraction.setWorld(world);
        deathHandler.setWorld(world);
        // Quarry mark, trap, and ability state reference entities from the old world
        rangerAbilities.reset();
        // Resonance is a combat-only resource; spawned zones/projectiles are gone with the old world
        arcanistAbilities.reset();
        // Doubt tracks entities from the old world; decoys are gone with it
        illusionistAbilities.reset();
        // Momentum and ability cooldowns reset; caltrop entities are gone with the old world
        rogueAbilities.reset();
    }
}
