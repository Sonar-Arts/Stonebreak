/**
 * Shader management for Open Mason rendering.
 *
 * <p>This package provides centralized shader management following Single Responsibility Principle.
 *
 * <h2>Core Components</h2>
 * <ul>
 *   <li>{@link com.openmason.main.systems.rendering.core.shaders.ShaderManager} - Creates and manages shader programs</li>
 *   <li>{@link com.openmason.main.systems.rendering.core.shaders.ShaderProgram} - Immutable shader program wrapper</li>
 *   <li>{@link com.openmason.main.systems.rendering.core.shaders.ShaderType} - Shader type enumeration</li>
 * </ul>
 *
 * <h2>Available Shader Types</h2>
 * <ul>
 *   <li><b>BASIC</b> - Simple geometry (position + color)</li>
 *   <li><b>MATRIX</b> - Per-part transforms with texture support</li>
 *   <li><b>GIZMO</b> - Transform gizmo rendering</li>
 *   <li><b>FACE</b> - Semi-transparent face overlays</li>
 *   <li><b>INFINITE_GRID</b> - Procedural infinite grid</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ShaderManager shaderManager = new ShaderManager();
 * shaderManager.initialize();
 *
 * ShaderProgram basicShader = shaderManager.getShaderProgram(ShaderType.BASIC);
 * basicShader.use();
 * basicShader.setMat4("uMVPMatrix", mvpMatrix);
 *
 * shaderManager.cleanup(); // On shutdown
 * }</pre>
 *
 * <h2>Migration Note</h2>
 * <p>This package replaces {@code com.openmason.main.systems.viewport.shaders}
 * which is now deprecated.
 *
 * @since 1.1
 */
package com.openmason.main.systems.rendering.core.shaders;
