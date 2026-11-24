package uk.appsthatmatter.koonj.server.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Component
final class McpProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(McpProtocol.class);
    private static final String VERSION = "2024-11-05";
    private final McpTools tools;
    private final ObjectMapper json;

    enum State { CONNECTED, INITIALIZING, READY }

    McpProtocol(McpTools tools, ObjectMapper json) {
        this.tools = tools;
        this.json = json;
    }

    Object handle(JsonNode request, AtomicReference<State> state, Authentication authentication) {
        if (!request.isArray()) return message(request, state, authentication);
        if (request.isEmpty() || request.size() > 16) return error(null, -32600, "Invalid JSON-RPC batch.");
        var responses = new ArrayList<>();
        for (JsonNode item : request) {
            Object response = message(item, state, authentication);
            if (response != null) responses.add(response);
        }
        return responses.isEmpty() ? null : responses;
    }

    private Object message(JsonNode request, AtomicReference<State> state, Authentication authentication) {
        JsonNode id = request.get("id");
        if (!request.isObject() || !"2.0".equals(request.path("jsonrpc").textValue())
                || !request.path("method").isTextual() || request.path("method").textValue().isEmpty()
                || (id != null && !id.isTextual() && !id.isIntegralNumber())) {
            return error(null, -32600, "Invalid JSON-RPC request.");
        }
        String method = request.path("method").textValue();
        JsonNode params = request.path("params");
        if (id == null) {
            if (method.equals("notifications/initialized") && (params.isMissingNode() || params.isObject())) {
                state.compareAndSet(State.INITIALIZING, State.READY);
            }
            return null;
        }
        if (!params.isMissingNode() && !params.isObject()) return error(id, -32602, "Expected an object for params.");
        try {
            return switch (method) {
                case "ping" -> success(id, Map.of());
                case "initialize" -> initialize(id, params, state);
                case "tools/list" -> state.get() == State.READY
                        ? success(id, Map.of("tools", tools.definitions(authentication))) : notReady(id);
                case "tools/call" -> state.get() == State.READY ? call(id, params, authentication) : notReady(id);
                default -> error(id, -32601, "Method not found.");
            };
        } catch (RuntimeException failure) {
            LOG.error("MCP request failed", failure);
            return error(id, -32603, "Internal error.");
        }
    }

    private Object initialize(JsonNode id, JsonNode params, AtomicReference<State> state) {
        if (!params.path("protocolVersion").isTextual() || params.path("protocolVersion").textValue().isBlank()
                || !params.path("capabilities").isObject() || !params.path("clientInfo").path("name").isTextual()
                || !params.path("clientInfo").path("version").isTextual()) {
            return error(id, -32602, "Expected protocolVersion, capabilities, and clientInfo.");
        }
        if (!state.compareAndSet(State.CONNECTED, State.INITIALIZING)) {
            return error(id, -32600, "Session is already initializing or initialized.");
        }
        return success(id, Map.of("protocolVersion", VERSION, "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "Koonj", "version", "1.0.0")));
    }

    private Object call(JsonNode id, JsonNode params, Authentication authentication) {
        String name = params.path("name").textValue();
        if (name == null || name.isBlank()) return error(id, -32602, "Expected a tool name.");
        try {
            Object result = tools.call(name, params.path("arguments"), authentication);
            return success(id, toolResult(json.writeValueAsString(result), false));
        } catch (McpTools.PermissionDenied | IllegalArgumentException failure) {
            return success(id, toolResult(failure.getMessage(), true));
        } catch (ResponseStatusException failure) {
            if (failure.getStatusCode().is5xxServerError()) {
                LOG.warn("MCP tool {} failed with HTTP {}", name, failure.getStatusCode());
                return success(id, toolResult("The tool could not complete the operation.", true));
            }
            return success(id, toolResult(Objects.requireNonNullElse(failure.getReason(), "The operation was rejected."), true));
        } catch (JsonProcessingException | RuntimeException failure) {
            LOG.error("MCP tool {} failed", name, failure);
            return success(id, toolResult("The tool could not complete the operation.", true));
        }
    }

    private static Map<String, Object> toolResult(String text, boolean isError) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", isError);
    }

    private static Object notReady(JsonNode id) {
        return error(id, -32000, "Initialize the MCP session before using tools.");
    }

    private static Map<String, Object> success(JsonNode id, Object result) {
        return Map.of("jsonrpc", "2.0", "id", id, "result", result);
    }

    static Map<String, Object> error(JsonNode id, int code, String message) {
        return Map.of("jsonrpc", "2.0", "id", id == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : id,
                "error", Map.of("code", code, "message", message));
    }
}
