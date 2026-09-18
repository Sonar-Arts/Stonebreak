package com.stonebreak.battle.api;

/** Every command the monk can submit. Costs and copy are the single source for model and HUD. */
public enum BattleCommand {
    STRIKE("Strike", Category.ROOT, 0, true,
            "A single focused blow. Builds a little Focus."),
    FLURRY("Flurry of Blows", Category.ROOT, 0, true,
            "Three rapid strikes. Time each hit on the ring for bonus damage and Focus."),
    STUNNING_STRIKE("Stunning Strike", Category.QI_ART, 2, true,
            "Strike a nerve: stuns the target, freezing its gauge and cancelling its attack."),
    SWIFT_STEP("Swift Step", Category.QI_ART, 1, true,
            "Step of the wind: Haste speeds your gauge for a short time."),
    MARTIAL_SURGE("Martial Surge", Category.QI_ART, 1, false,
            "Queue extra hits on your next Strike or Flurry. Does not end your turn."),
    MEDITATE("Meditate", Category.ROOT, 0, true,
            "Wholeness of body: restore health. Limited uses."),
    GUARD("Guard", Category.ROOT, 0, true,
            "Patient defense: halve the next blow. Press confirm as it lands to parry."),
    FOCUS_COMBO("Focus Combo", Category.ROOT, 0, true,
            "Unleash the ultimate combo. Requires a full Focus gauge.");

    public enum Category { ROOT, QI_ART }

    private final String displayName;
    private final Category category;
    private final int qiCost;
    private final boolean endsTurn;
    private final String helpText;

    BattleCommand(String displayName, Category category, int qiCost, boolean endsTurn, String helpText) {
        this.displayName = displayName;
        this.category = category;
        this.qiCost = qiCost;
        this.endsTurn = endsTurn;
        this.helpText = helpText;
    }

    public String displayName() { return displayName; }
    public Category category() { return category; }
    public int qiCost() { return qiCost; }
    /** False for free actions (Martial Surge) that leave the command window open. */
    public boolean endsTurn() { return endsTurn; }
    public String helpText() { return helpText; }
}
