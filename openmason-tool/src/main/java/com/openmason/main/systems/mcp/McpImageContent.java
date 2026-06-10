package com.openmason.main.systems.mcp;

/**
 * Marker result type for tools that return an image instead of JSON text.
 *
 * <p>When a tool handler returns this, {@link McpRequestRouter} emits an MCP
 * {@code image} content block ({@code {type:"image", data:<base64>, mimeType:...}})
 * rather than serializing the result as a JSON string. LLM clients tokenize
 * images by pixel dimensions, so producers should downscale before encoding.
 */
public record McpImageContent(String base64Data, String mimeType) {}
