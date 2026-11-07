package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.world.TimeOfDay;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * /timeset clock-time parsing: numbers are the clock the game displays, not raw ticks.
 */
class TimeSetCommandTest {

    @Test
    void bareNumberIsTheHourOnA24HourClock() {
        assertEquals(TimeOfDay.DAWN, TimeSetCommand.parseClockTime("6"));
        assertEquals(TimeOfDay.NOON, TimeSetCommand.parseClockTime("12"));
        assertEquals(TimeOfDay.DUSK, TimeSetCommand.parseClockTime("18"));
        assertEquals(TimeOfDay.MIDNIGHT, TimeSetCommand.parseClockTime("0"));
    }

    @Test
    void hoursAndMinutes() {
        assertEquals(TimeOfDay.NOON + 500, TimeSetCommand.parseClockTime("12:30"));
        assertEquals(TimeOfDay.MIDNIGHT, TimeSetCommand.parseClockTime("00:00"));
    }

    @Test
    void colonIsOptional() {
        assertEquals(TimeOfDay.ticksAtClock(14, 30), TimeSetCommand.parseClockTime("1430"));
        assertEquals(TimeOfDay.DAWN + 500, TimeSetCommand.parseClockTime("630"));
        assertEquals(TimeOfDay.DAWN + 500, TimeSetCommand.parseClockTime("0630"));
        assertEquals(TimeOfDay.MIDNIGHT, TimeSetCommand.parseClockTime("0000"));
        assertEquals(TimeOfDay.ticksAtClock(14, 30), TimeSetCommand.parseClockTime("14 30"));
        assertEquals(TimeOfDay.ticksAtClock(14, 30), TimeSetCommand.parseClockTime("14.30"));
        assertEquals(TimeOfDay.ticksAtClock(14, 30), TimeSetCommand.parseClockTime("14;30"));
        assertEquals(TimeOfDay.DUSK + 500, TimeSetCommand.parseClockTime("630pm"));
        assertEquals(TimeOfDay.DUSK + 500, TimeSetCommand.parseClockTime("630 pm"));
    }

    @Test
    void amPm() {
        assertEquals(TimeOfDay.DUSK, TimeSetCommand.parseClockTime("6pm"));
        assertEquals(TimeOfDay.DUSK, TimeSetCommand.parseClockTime("6 PM"));
        assertEquals(TimeOfDay.DAWN + 500, TimeSetCommand.parseClockTime("6:30am"));
        assertEquals(TimeOfDay.MIDNIGHT, TimeSetCommand.parseClockTime("12am"));
        assertEquals(TimeOfDay.NOON, TimeSetCommand.parseClockTime("12pm"));
    }

    @Test
    void rejectsWhatIsNotAClockTime() {
        assertEquals(-1, TimeSetCommand.parseClockTime("24"));
        assertEquals(-1, TimeSetCommand.parseClockTime("6000")); // raw ticks go through /timeset ticks
        assertEquals(-1, TimeSetCommand.parseClockTime("12:60"));
        assertEquals(-1, TimeSetCommand.parseClockTime("1260"));
        assertEquals(-1, TimeSetCommand.parseClockTime("2400"));
        assertEquals(-1, TimeSetCommand.parseClockTime("14305"));
        assertEquals(-1, TimeSetCommand.parseClockTime("13pm"));
        assertEquals(-1, TimeSetCommand.parseClockTime("0am"));
        assertEquals(-1, TimeSetCommand.parseClockTime("-1"));
        assertEquals(-1, TimeSetCommand.parseClockTime("noonish"));
    }
}
