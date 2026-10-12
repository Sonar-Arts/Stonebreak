package com.openmason.main.systems.scripting.mcp;

import com.openmason.main.systems.scripting.ScriptExecutor.Language;
import com.openmason.main.systems.scripting.ScriptExecutor.ScriptSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A script that only edits UI documents must not auto-create a model or a model undo entry (#324). */
class ScriptingServiceUiOnlyTest {

    private static boolean uiOnly(String python) {
        return ScriptingService.uiOnly(new ScriptSource(Language.PYTHON, "script.py", python));
    }

    @Test
    void detectsUiOnlyScripts() {
        assertTrue(uiOnly("import om, math\np = om.ui.create('Box')\nom.ui.set_style(p, {'width': om.math.pi})"));
        assertFalse(uiOnly("import om\nom.box('body')\nom.ui.create('Box')"), "mixed scripts touch the model");
        assertFalse(uiOnly("import om\nom.box('body')"));
        assertFalse(uiOnly("print('no om at all')"));
        assertFalse(ScriptingService.uiOnly(new ScriptSource(Language.JSON_OPS, "ops.json", "{\"ops\":[]}")));
    }
}
