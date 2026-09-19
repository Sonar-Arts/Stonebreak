package com.stonebreak.battle.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable hand-set {@link BattleView} for HUD and camera tests. Set the public fields, then hand it
 * to the code under test. Not a test class itself (shared fixture).
 */
public final class FakeBattleView implements BattleView {

    public static final class FakeCombatant implements CombatantView {
        public final CombatantId id;
        public String displayName;
        public float hp;
        public float maxHp;
        public float atb;
        public ActorPose pose = ActorPose.IDLE;
        public final List<StatusView> statuses = new ArrayList<>();

        public FakeCombatant(CombatantId id, String displayName, float maxHp) {
            this.id = id;
            this.displayName = displayName;
            this.maxHp = maxHp;
            this.hp = maxHp;
        }

        @Override public CombatantId id() { return id; }
        @Override public String displayName() { return displayName; }
        @Override public float hp() { return hp; }
        @Override public float maxHp() { return maxHp; }
        @Override public float atb() { return atb; }
        @Override public ActorPose pose() { return pose; }
        @Override public List<StatusView> statuses() { return List.copyOf(statuses); }
    }

    public BattlePhase phase = BattlePhase.RUNNING;
    public BattleOutcome outcome = BattleOutcome.NONE;
    public float elapsedSeconds;
    public final FakeCombatant monk = new FakeCombatant(CombatantId.MONK, "Monk", 180f);
    public final FakeCombatant archon = new FakeCombatant(CombatantId.ARCHON, "Ice Archon", 900f);
    public int qi = 3;
    public int maxQi = 5;
    public float focus;
    public float maxFocus = 100f;
    public int meditateCharges = 3;
    public int queuedSurgeHits;
    public boolean commandWindowOpen;
    /** Commands listed here report unavailable with the paired reason. */
    public final java.util.Map<BattleCommand, String> unavailable = new java.util.EnumMap<>(BattleCommand.class);
    public ActionView currentAction;
    public TelegraphView telegraph;
    public PromptView prompt;
    public BattleStats stats = BattleStats.ZERO;
    public final List<BattleEvent> events = new ArrayList<>();

    @Override public BattlePhase phase() { return phase; }
    @Override public BattleOutcome outcome() { return outcome; }
    @Override public float elapsedSeconds() { return elapsedSeconds; }
    @Override public CombatantView monk() { return monk; }
    @Override public CombatantView archon() { return archon; }
    @Override public int qi() { return qi; }
    @Override public int maxQi() { return maxQi; }
    @Override public float focus() { return focus; }
    @Override public float maxFocus() { return maxFocus; }
    @Override public int meditateCharges() { return meditateCharges; }
    @Override public int queuedSurgeHits() { return queuedSurgeHits; }
    @Override public boolean commandWindowOpen() { return commandWindowOpen; }
    @Override public CommandAvailability availability(BattleCommand command) {
        String reason = unavailable.get(command);
        return reason == null ? CommandAvailability.OK : CommandAvailability.no(reason);
    }
    @Override public ActionView currentAction() { return currentAction; }
    @Override public TelegraphView telegraph() { return telegraph; }
    @Override public PromptView prompt() { return prompt; }
    @Override public BattleStats stats() { return stats; }
    @Override public List<BattleEvent> frameEvents() { return List.copyOf(events); }

    /** The Frostbound Crucible layout without obstacles, for tests that only need geometry. */
    public static BattleStageLayout crucibleLayout() {
        return new BattleStageLayout(new org.joml.Vector3f(0f, 0.06f, 9f), new org.joml.Vector3f(0f, 0.06f, -9f),
                1.8f, 2.72f, 2.0f, 0.35f, 50f, 0.06f, List.of());
    }
}
