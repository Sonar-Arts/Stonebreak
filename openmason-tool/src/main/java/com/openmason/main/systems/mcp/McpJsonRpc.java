package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 envelope types plus MCP-specific error codes.
 */
public final class McpJsonRpc {

    private McpJsonRpc() {}

    public static final String JSONRPC = "2.0";

    /** Standard JSON-RPC error codes. */
    public static final int PARSE_ERROR      = -32700;
    public static final int INVALID_REQUEST  = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS   = -32602;
    public static final int INTERNAL_ERROR   = -32603;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(String jsonrpc, Object id, String method, JsonNode params) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(String jsonrpc, Object id, Object result, Error error) {
        public static Response ok(Object id, Object result) {
            return new Response(JSONRPC, id, result, null);
        }

        public static Response fail(Object id, int code, String message) {
            return new Response(JSONRPC, id, null, new Error(code, message, null));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(int code, String message, Object data) {}
}
