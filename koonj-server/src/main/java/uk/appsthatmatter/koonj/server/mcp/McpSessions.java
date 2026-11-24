package uk.appsthatmatter.koonj.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uk.appsthatmatter.koonj.server.security.ApiKeyService;
import uk.appsthatmatter.koonj.server.security.ProjectScope;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

@Component
final class McpSessions {
    private static final Logger LOG = LoggerFactory.getLogger(McpSessions.class);
    private static final long SESSION_TIMEOUT = Duration.ofHours(24).toMillis();
    private static final int SESSIONS_PER_KEY = 16;
    private static final int CALLS_PER_SESSION = 8;

    private final ApiKeyService keys;
    private final McpProtocol protocol;
    private final int maxSessions;
    private final Duration heartbeatInterval;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore calls;

    McpSessions(ApiKeyService keys, McpProtocol protocol,
                @Value("${koonj.mcp.max-sessions:512}") int maxSessions,
                @Value("${koonj.mcp.max-calls:64}") int maxCalls,
                @Value("${koonj.mcp.heartbeat-interval:20s}") Duration heartbeatInterval) {
        this.keys = keys;
        this.protocol = protocol;
        this.maxSessions = Math.max(1, maxSessions);
        this.calls = new Semaphore(Math.max(1, maxCalls));
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("MCP heartbeat interval must be positive");
        }
        this.heartbeatInterval = heartbeatInterval;
    }

    SseEmitter open(ApiKeyService.Principal owner, String messagePath) {
        var session = new Session(owner);
        synchronized (sessions) {
            if (sessions.size() >= maxSessions || sessions.values().stream()
                    .filter(existing -> existing.owner.keyId().equals(owner.keyId())).count() >= SESSIONS_PER_KEY) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "MCP session limit reached");
            }
            sessions.put(session.id, session);
        }
        session.events.onCompletion(session::close);
        session.events.onTimeout(session::close);
        session.events.onError(failure -> session.close());
        session.send(SseEmitter.event().name("endpoint").data(messagePath + "?sessionId=" + session.id));
        session.heartbeat.start();
        return session.events;
    }

    Session get(UUID id, ApiKeyService.Principal owner) {
        Session session;
        synchronized (sessions) {
            session = sessions.get(id);
        }
        if (session == null || !session.owner.equals(owner)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP session was not found");
        }
        return session;
    }

    void submit(Session session, Authentication authentication, JsonNode request) {
        String method = request.path("method").asText();
        // Process handshake messages before accepting the POST so later POSTs see the new state.
        if ("initialize".equals(method) || "notifications/initialized".equals(method) || "ping".equals(method)) {
            session.result(protocol.handle(request, session.state, authentication));
            return;
        }
        if (!session.inFlight.tryAcquire()) throw busy();
        if (!calls.tryAcquire()) {
            session.inFlight.release();
            throw busy();
        }
        try {
            workers.execute(() -> {
                try {
                    if (!keys.isActive(session.owner)) {
                        session.close();
                        return;
                    }
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(authentication);
                    SecurityContextHolder.setContext(context);
                    ProjectScope.run(session.owner.projectId(), () ->
                            session.result(protocol.handle(request, session.state, authentication)));
                } catch (RuntimeException failure) {
                    LOG.warn("MCP session processing failed", failure);
                    session.result(McpProtocol.error(request.get("id"), -32603, "Internal error."));
                } finally {
                    SecurityContextHolder.clearContext();
                    session.inFlight.release();
                    calls.release();
                }
            });
        } catch (RuntimeException failure) {
            session.inFlight.release();
            calls.release();
            throw failure;
        }
    }

    private static ResponseStatusException busy() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many concurrent MCP calls");
    }

    @PreDestroy
    void stop() {
        List<Session> remaining;
        synchronized (sessions) {
            remaining = List.copyOf(sessions.values());
        }
        remaining.forEach(Session::close);
        workers.shutdownNow();
    }

    final class Session {
        private final UUID id = UUID.randomUUID();
        private final ApiKeyService.Principal owner;
        private final SseEmitter events = new SseEmitter(SESSION_TIMEOUT);
        private final AtomicReference<McpProtocol.State> state = new AtomicReference<>(McpProtocol.State.CONNECTED);
        private final Semaphore inFlight = new Semaphore(CALLS_PER_SESSION);
        private final Thread heartbeat = Thread.ofVirtual().name("mcp-heartbeat").unstarted(this::keepAlive);

        private Session(ApiKeyService.Principal owner) { this.owner = owner; }

        void result(Object response) {
            if (response != null) send(SseEmitter.event().name("message").data(response, MediaType.APPLICATION_JSON));
        }

        private void keepAlive() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(heartbeatInterval);
                    if (!keys.isActive(owner)) break;
                    send(SseEmitter.event().comment("keep-alive"));
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException failure) {
                LOG.warn("MCP heartbeat failed", failure);
            } finally {
                close();
            }
        }

        private void send(SseEmitter.SseEventBuilder event) {
            try {
                events.send(event);
            } catch (IOException | IllegalStateException failure) {
                close();
            }
        }

        private void close() {
            synchronized (sessions) {
                if (!sessions.remove(id, this)) return;
            }
            heartbeat.interrupt();
            events.complete();
        }
    }
}
