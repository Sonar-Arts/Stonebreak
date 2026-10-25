package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.StubMob;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.mobs.entities.ai.AwarenessController.AwarenessState;
import com.stonebreak.mobs.entities.ai.nav.PathAgent;
import com.stonebreak.mobs.entities.ai.nav.Steering;
import com.stonebreak.mobs.entities.ai.perception.LightProbe;
import com.stonebreak.mobs.entities.ai.perception.NoiseBus;
import com.stonebreak.mobs.entities.ai.perception.PerceptionProfile;
import com.stonebreak.mobs.entities.ai.perception.ShoutRelay;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A goblin that lost the player goes where it last perceived them, and follows fresh news (#321). */
class SearchBehaviorTest {

    private final StubMob mob = new StubMob(EntityType.GOBLIN, new Vector3f(0, 64, 0));
    private final PathAgent nav = new PathAgent(mob, new Steering(mob, 360f, 0f, 0f));
    private final AiContext context = new AiContext(mob, nav, new Random(5), PlayerLocator.NONE);
    private final AwarenessController awareness = new AwarenessController(mob, PerceptionProfile.GOBLIN,
            PlayerLocator.NONE, LightProbe.FULL, ShoutRelay.NONE, new NoiseBus());
    private final SearchBehavior search = new SearchBehavior(awareness, 0.8f);

    /** Lost the player at {@code where}: alerted toward it, then searching. */
    private void lostThePlayerAt(Vector3f where) {
        awareness.onAttacked(where);
        awareness.applyReplicatedState(AwarenessState.SEARCHING);
    }

    @Test
    void onlyASearchingGoblinSearches() {
        assertFalse(search.canStart(context));
        awareness.onAttacked(new Vector3f(4, 64, 4));
        assertFalse(search.canStart(context), "alerted: that is the pursuit's job");
        awareness.applyReplicatedState(AwarenessState.SEARCHING);
        assertTrue(search.canStart(context));
    }

    @Test
    void itHeadsForWhereThePlayerWasLastPerceived() {
        Vector3f lost = new Vector3f(6, 64, -3);
        lostThePlayerAt(lost);
        search.start(context);
        assertTrue(nav.hasGoal());
        assertEquals(lost.x, nav.goal(new Vector3f()).x, 1e-4f);
    }

    @Test
    void aPackmatesShoutMovesTheSearch() {
        lostThePlayerAt(new Vector3f(6, 64, -3));
        search.start(context);
        Vector3f reported = new Vector3f(-8, 64, 10);
        awareness.hearShout(reported);
        context.setDeltaTime(0.05f);
        search.tick(context, 0.05f);
        assertEquals(reported.z, nav.goal(new Vector3f()).z, 1e-4f);
    }
}
