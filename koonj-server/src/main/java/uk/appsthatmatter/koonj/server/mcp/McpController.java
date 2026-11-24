package uk.appsthatmatter.koonj.server.mcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uk.appsthatmatter.koonj.server.security.ApiKeyService;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/mcp")
public class McpController {
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private final McpSessions sessions;
    private final ObjectReader json;

    public McpController(McpSessions sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.json = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal ApiKeyService.Principal principal,
                                            HttpServletRequest request) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-transform")
                .header("X-Accel-Buffering", "no")
                .header("Referrer-Policy", "no-referrer")
                .body(sessions.open(principal, request.getContextPath() + "/api/v1/mcp/message"));
    }

    @PostMapping(value = "/message", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void message(@RequestParam UUID sessionId, @AuthenticationPrincipal ApiKeyService.Principal principal,
                        Authentication authentication, HttpServletRequest request) throws IOException {
        var session = sessions.get(sessionId, principal);
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) throw tooLarge();
        byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) throw tooLarge();
        try {
            sessions.submit(session, authentication, json.readValue(body));
        } catch (JsonProcessingException failure) {
            session.result(McpProtocol.error(null, -32700, "Invalid JSON."));
        }
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "MCP request exceeds 64 KiB");
    }
}
