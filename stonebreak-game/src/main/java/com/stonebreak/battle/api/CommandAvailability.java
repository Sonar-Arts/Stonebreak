package com.stonebreak.battle.api;

/** Whether a command can be submitted right now, and if not, a short player-facing reason. */
public record CommandAvailability(boolean available, String reason) {
    public static final CommandAvailability OK = new CommandAvailability(true, "");

    public static CommandAvailability no(String reason) {
        return new CommandAvailability(false, reason);
    }
}
