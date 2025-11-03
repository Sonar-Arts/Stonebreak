package com.openmason.ui.components.modelBrowser.events;

import com.openmason.ui.components.modelBrowser.events.listeners.BlockSelectionListener;
import com.openmason.ui.components.modelBrowser.events.listeners.ItemSelectionListener;
import com.openmason.ui.components.modelBrowser.events.listeners.ModelSelectionListener;

/**
 * Composite listener interface for all Model Browser events.
 *
 * <p>This interface combines all three focused listener interfaces, providing a
 * convenient option for components that need to handle all selection types.
 * Implements the Observer pattern for loose coupling between the Model Browser and viewport.</p>
 *
 * <p>Following SOLID principles:</p>
 * <ul>
 *   <li><strong>Interface Segregation Principle (ISP)</strong>: Components can implement
 *       only the specific listener interfaces they need ({@link BlockSelectionListener},
 *       {@link ItemSelectionListener}, {@link ModelSelectionListener}), or this composite
 *       interface if they handle all types.</li>
 *   <li><strong>Dependency Inversion</strong>: Depend on abstraction, not concrete implementation</li>
 * </ul>
 *
 * <h3>Usage Examples</h3>
 * <pre>{@code
 * // Component that handles all selection types
 * public class Viewport implements ModelBrowserListener {
 *     // Must implement all three methods
 * }
 *
 * // Component that only cares about blocks
 * public class BlockInspector implements BlockSelectionListener {
 *     // Only implements onBlockSelected
 * }
 * }</pre>
 *
 * @see BlockSelectionListener
 * @see ItemSelectionListener
 * @see ModelSelectionListener
 */
public interface ModelBrowserListener extends
        BlockSelectionListener,
        ItemSelectionListener,
        ModelSelectionListener {
    // This interface intentionally has no methods
    // It inherits all methods from the three focused interfaces
}
