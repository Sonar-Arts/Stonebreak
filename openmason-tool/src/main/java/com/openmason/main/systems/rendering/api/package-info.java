/**
 * Unified Rendering API for Open Mason.
 *
 * <p>This package provides a consistent rendering framework following SOLID, DRY, KISS, and YAGNI principles.
 *
 * <h2>Core Components</h2>
 * <ul>
 *   <li>{@link com.openmason.main.systems.rendering.api.IRenderer} - Core renderer contract</li>
 *   <li>{@link com.openmason.main.systems.rendering.api.BaseRenderer} - Abstract base with Template Method pattern</li>
 *   <li>{@link com.openmason.main.systems.rendering.api.RenderingController} - Master coordinator</li>
 *   <li>{@link com.openmason.main.systems.rendering.api.RenderPass} - Pass ordering enum</li>
 *   <li>{@link com.openmason.main.systems.rendering.api.GeometryData} - Geometry data holder</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <p>For simple renderers, extend {@link com.openmason.main.systems.rendering.api.BaseRenderer}:
 * <pre>{@code
 * public class MyRenderer extends BaseRenderer {
 *     @Override public String getDebugName() { return "MyRenderer"; }
 *     @Override public RenderPass getRenderPass() { return RenderPass.SCENE; }
 *     @Override protected GeometryData createGeometry() { ... }
 *     @Override protected void configureVertexAttributes() { ... }
 *     @Override protected void doRender(ShaderProgram shader, RenderContext context) { ... }
 * }
 * }</pre>
 *
 * <p>For complex renderers with custom logic, implement {@link com.openmason.main.systems.rendering.api.IRenderer} directly.
 *
 * <h2>Render Passes</h2>
 * <p>Rendering occurs in ordered passes:
 * <ol>
 *   <li><b>BACKGROUND</b> - Grid, skybox, environment</li>
 *   <li><b>SCENE</b> - Models, blocks, items</li>
 *   <li><b>OVERLAY</b> - Vertices, edges, faces</li>
 *   <li><b>UI</b> - Gizmos, handles</li>
 *   <li><b>DEBUG</b> - Bounding boxes, normals</li>
 * </ol>
 *
 * <h2>Design Principles</h2>
 * <table border="1">
 *   <tr><th>Principle</th><th>Application</th></tr>
 *   <tr><td>KISS</td><td>Simple IRenderer interface (8 methods)</td></tr>
 *   <tr><td>SOLID-S</td><td>Each renderer has single responsibility</td></tr>
 *   <tr><td>SOLID-O</td><td>New renderers extend BaseRenderer without modifying it</td></tr>
 *   <tr><td>SOLID-L</td><td>All IRenderer implementations are interchangeable</td></tr>
 *   <tr><td>SOLID-I</td><td>IRenderer only includes essential methods</td></tr>
 *   <tr><td>SOLID-D</td><td>RenderingController depends on IRenderer, not concrete types</td></tr>
 *   <tr><td>DRY</td><td>BaseRenderer eliminates ~40 lines duplication per renderer</td></tr>
 *   <tr><td>YAGNI</td><td>Only implemented features that are needed now</td></tr>
 * </table>
 *
 * <h2>Migration Guide</h2>
 * <p>Existing renderers (GridRenderer, VertexRenderer, EdgeRenderer, FaceRenderer) follow
 * similar patterns but have not been migrated to extend BaseRenderer due to their complex
 * custom logic. They can be used alongside the new API.
 *
 * @see com.openmason.main.systems.rendering.model.GenericModelRenderer Example renderer using this API
 * @since 1.1
 */
package com.openmason.main.systems.rendering.api;
