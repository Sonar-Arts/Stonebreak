package com.openmason.main.systems.assistant.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolAccessPolicyTest {

    @Test
    void categories() {
        assertEquals(ToolCategory.REQUIRES_AUTH, ToolAccessPolicy.categorize("asset_open"));
        assertEquals(ToolCategory.READ_ONLY, ToolAccessPolicy.categorize("model_summary"));
        assertEquals(ToolCategory.READ_ONLY, ToolAccessPolicy.categorize("asset_list"));
        assertEquals(ToolCategory.READ_ONLY, ToolAccessPolicy.categorize("get_part"));
        assertEquals(ToolCategory.READ_ONLY, ToolAccessPolicy.categorize("list_parts"));
        assertEquals(ToolCategory.READ_ONLY, ToolAccessPolicy.categorize("knowledge"));
        assertEquals(ToolCategory.MUTATING, ToolAccessPolicy.categorize("create_part"));
        assertEquals(ToolCategory.MUTATING, ToolAccessPolicy.categorize("run_python_script"));
        assertEquals(ToolCategory.MUTATING, ToolAccessPolicy.categorize("tex_open_editor"));
    }

    @Test
    void unknownDefaultsToMutating() {
        assertEquals(ToolCategory.MUTATING, ToolAccessPolicy.categorize("brand_new_tool"));
        assertEquals(ToolCategory.MUTATING, ToolAccessPolicy.categorize(null));
        assertEquals(ToolCategory.MUTATING, ToolAccessPolicy.categorize(""));
    }
}
