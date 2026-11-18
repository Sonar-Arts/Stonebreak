package com.stonebreak.audio;

import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The mixer's origin follows the listener on a grid, so mixer floats stay exact anywhere in the 64-bit world. */
class AudioSpaceTest {

    @AfterEach
    void reset() {
        AudioSpace.reset();
    }

    @Test
    void nearTheWorldOriginNothingMoves() {
        AudioSpace.setListener(null, new Vector3d(10.5, 70, -3.25), new Vector3f(0, 0, -1), new Vector3f(0, 1, 0));
        assertArrayEquals(new double[]{0, 0, -AudioSpace.GRID}, AudioSpace.origin());
        assertEquals(10.5f, AudioSpace.x(10.5));
        assertEquals(70f, AudioSpace.y(70));
    }

    @Test
    void farAwaySoundsAreExactRelativeToTheListener() {
        double far = 1_234_567_890.25;
        AudioSpace.setListener(null, new Vector3d(far, 64, -far), new Vector3f(0, 0, -1), new Vector3f(0, 1, 0));
        // a sound 3.125 blocks east of the listener, in mixer space
        float listenerX = AudioSpace.x(far);
        float soundX = AudioSpace.x(far + 3.125);
        assertEquals(3.125f, soundX - listenerX, 1e-4f);
        Vector3f m = AudioSpace.toMixer(far + 1, 65, -far - 1, new Vector3f());
        assertEquals(1f, m.x - listenerX, 1e-4f);
        assertEquals(-1f, m.z - AudioSpace.z(-far), 1e-4f);
    }

    @Test
    void theOriginOnlyMovesWhenTheListenerLeavesItsCell() {
        Vector3f f = new Vector3f(0, 0, -1), u = new Vector3f(0, 1, 0);
        AudioSpace.setListener(null, new Vector3d(5000, 0, 0), f, u);
        double[] first = AudioSpace.origin();
        AudioSpace.setListener(null, new Vector3d(5100, 0, 0), f, u);
        assertArrayEquals(first, AudioSpace.origin(), "same cell: no move");
        AudioSpace.setListener(null, new Vector3d(5000 + AudioSpace.GRID, 0, 0), f, u);
        assertEquals(first[0] + AudioSpace.GRID, AudioSpace.origin()[0]);
    }
}
