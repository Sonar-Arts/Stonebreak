package com.stonebreak.battle.api;

/** Whether a command can be submitted right now, and if not, a short player-facing reason. */
public record CommandAvailability(boolean available, String reason) {
    public static final CommandAvailability OK = new CommandAvailability(true, "");

    /**
     * The reason reported when a command is affordable but the monk simply cannot act yet. The model
     * reports resource shortfalls (Qi, Focus, charges) in preference to this, so a HUD resting between
     * turns can still dim exactly the rows that will be unusable when the turn comes. Qi is judged
     * against what the monk will hold once the coming turn has granted its Qi.
     */
    public static final String NOT_YOUR_TURN = "Not your turn";

    /** True when the only thing in the way is the turn order. */
    public boolean onlyWaitingForTurn() {
        return !available && NOT_YOUR_TURN.equals(reason);
    }

    public static CommandAvailability no(String reason) {
        return new CommandAvailability(false, reason);
    }
}
