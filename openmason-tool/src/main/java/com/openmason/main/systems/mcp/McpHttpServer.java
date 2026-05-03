package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embedded HTTP server exposing the MCP streamable-HTTP transport.
 *
 * <p>Single endpoint at {@code /mcp} accepts JSON-RPC POSTs and returns
 * JSON responses. No SSE; tool calls complete synchronously over the request.
 *
 * <p>Bound to {@code 127.0.0.1} only. Not intended to be reachable off-host.
 */
public final class McpHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(McpHttpServer.class);

    private final int port;
    private final McpRequestRouter router;
    private final ObjectMapper mapper;

    private HttpServer server;

    public McpHttpServer(int port, McpRequestRouter router, ObjectMapper mapper) {
        this.port = port;
        this.router = router;
        this.mapper = mapper;
    }

    public void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("MCP server already started");
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/mcp", new McpHandler());
        server.setExecutor(Executors.newCachedThreadPool(new NamedThreadFactory("OpenMason-MCP")));
        server.start();
        logger.info("MCP server listening on http://127.0.0.1:{}/mcp", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
            logger.info("MCP server stopped");
        }
    }

    private final class McpHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                if ("OPTIONS".equalsIgnoreCase(method)) {
                    sendNoContent(exchange);
                    return;
                }
                if (!"POST".equalsIgnoreCase(method)) {
                    sendStatus(exchange, 405, "Method Not Allowed");
                    return;
                }

                byte[] requestBody;
                try (InputStream in = exchange.getRequestBody()) {
                    requestBody = in.readAllBytes();
                }

                McpJsonRpc.Request request;
                try {
                    request = mapper.readValue(requestBody, McpJsonRpc.Request.class);
                } catch (Exception e) {
                    McpJsonRpc.Response parseFail = McpJsonRpc.Response.fail(
                            null, McpJsonRpc.PARSE_ERROR, "Parse error: " + e.getMessage());
                    sendJson(exchange, 200, parseFail);
                    return;
                }

                McpJsonRpc.Response response = router.handle(request);
                if (response == null) {
                    // Notification — no body, 202 per MCP spec.
                    sendStatus(exchange, 202, "Accepted");
                    return;
                }
                sendJson(exchange, 200, response);
            } catch (Exception e) {
                logger.error("Unhandled MCP request error", e);
                try {
                    sendStatus(exchange, 500, "Internal Server Error");
                } catch (IOException ignored) {
                }
            } finally {
                exchange.close();
            }
        }

        private void sendJson(HttpExchange exchange, int code, Object body) throws IOException {
            byte[] bytes = mapper.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            applyCors(exchange);
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        private void sendStatus(HttpExchange exchange, int code, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            applyCors(exchange);
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        private void sendNoContent(HttpExchange exchange) throws IOException {
            applyCors(exchange);
            exchange.sendResponseHeaders(204, -1);
        }

        private void applyCors(HttpExchange exchange) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
