package uk.appsthatmatter.koonj.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import uk.appsthatmatter.koonj.server.entity.FileMetadata;
import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;
import uk.appsthatmatter.koonj.server.entity.StorageRole;
import uk.appsthatmatter.koonj.server.repository.FileMetadataRepository;
import uk.appsthatmatter.koonj.server.repository.StorageConnectionRepository;
import uk.appsthatmatter.koonj.server.repository.UserApiKeyRepository;
import uk.appsthatmatter.koonj.server.security.ProjectScope;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:mcp-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "koonj.mcp.heartbeat-interval=1s"
})
class McpIntegrationTest {
    private static final String BASE = "/api/v1";
    private static final String SSE = BASE + "/mcp/sse";
    private static final String ACCOUNT = BASE + "/account/tokens";
    private static final String ADMIN = "mcp-admin";
    private static final String VIEWER = "mcp-viewer";

    @LocalServerPort int port;
    @TempDir Path root;
    @Autowired ObjectMapper json;
    @Autowired UserApiKeyRepository keys;
    @Autowired StorageConnectionRepository connections;
    @Autowired FileMetadataRepository files;

    @Test
    @Timeout(60)
    void issuesProjectBoundKeysAndServesAuthorizedMcpCalls() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            assertThat(send(client, "GET", SSE, null, null, null).statusCode()).isEqualTo(401);
            assertThat(send(client, "GET", SSE, ADMIN, null, null).statusCode()).isEqualTo(401);
            assertThat(send(client, "POST", BASE + "/mcp/message", null, null, "{}").statusCode()).isEqualTo(401);
            assertThat(send(client, "POST", BASE + "/projects/session", ADMIN, null, "{}").statusCode()).isEqualTo(200);
            assertThat(send(client, "POST", BASE + "/projects/session", VIEWER, null, "{}").statusCode()).isEqualTo(200);
            String project = json.readTree(send(client, "POST", BASE + "/projects", ADMIN, null,
                    "{\"name\":\"MCP workspace\"}").body()).path("id").asText();
            assertThat(project).isNotBlank();
            assertThat(send(client, "PUT", BASE + "/projects/" + project + "/members", ADMIN, null,
                    "{\"subject\":\"oidc:" + VIEWER + "\"}").statusCode()).isEqualTo(204);

            var location = fixture(UUID.fromString(project));
            fixture(ProjectScope.LEGACY);
            assertThat(send(client, "POST", ACCOUNT, ADMIN, project,
                    "{\"name\":\"Expired\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}").statusCode()).isEqualTo(400);
            var issued = issue(client, ADMIN, project);
            String token = issued.path("token").asText();
            UUID keyId = UUID.fromString(issued.path("metadata").path("id").asText());
            assertThat(token).matches("kj_live_[A-Za-z0-9_-]{43}");
            assertThat(keys.findById(keyId).orElseThrow().getTokenHash()).matches("[0-9a-f]{64}").doesNotContain(token);
            assertThat(send(client, "GET", ACCOUNT, token, project, null).statusCode()).isEqualTo(401);
            assertThat(send(client, "GET", SSE, token, ProjectScope.LEGACY_VALUE, null).statusCode()).isEqualTo(403);
            assertThat(send(client, "GET", ACCOUNT, ADMIN, project, null).body()).doesNotContain(token, "tokenHash");
            assertThat(send(client, "GET", ACCOUNT, VIEWER, project, null).body()).isEqualTo("[]");

            String viewerKey = issue(client, VIEWER, project).path("token").asText();
            try (var admin = open(client, token, project, false); var viewer = open(client, viewerKey, project, true)) {
                assertThat(send(client, "POST", admin.endpoint, viewerKey, project, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")
                        .statusCode()).isEqualTo(404);
                assertThat(admin.tools().findValuesAsText("name"))
                        .contains("search_catalog_files", "get_job_status", "control_job");
                assertThat(viewer.tools().findValuesAsText("name")).contains("search_catalog_files").doesNotContain("queue_archive");
                var denied = viewer.call("queue_archive", Map.of());
                assertThat(denied.path("isError").asBoolean()).isTrue();
                assertThat(denied.path("content").get(0).path("text").asText())
                        .isEqualTo("Permission denied: Required permission [TRANSFER_RUN] missing.");

                var page = payload(viewer.call("search_catalog_files", Map.of("storageState", "HOT", "pathPrefix", "film")));
                assertThat(page.path("items")).hasSize(1);
                assertThat(page.path("items").get(0).path("connectionId").asText()).isEqualTo(location.getId().toString());
                assertThat(viewer.call("search_catalog_files", Map.of("limit", 51)).path("isError").asBoolean()).isTrue();
                viewer.awaitHeartbeat();
                var scan = payload(admin.call("trigger_catalog_scan", Map.of("connectionId", location.getId())));
                assertThat(scan.path("status").asText()).isEqualTo("QUEUED");
                String jobId = scan.path("jobId").asText();
                var status = payload(admin.call("get_job_status", Map.of("jobId", jobId)));
                assertThat(status.path("jobId").asText()).isEqualTo(jobId);
                assertThat(status.path("errors").path("content").isArray()).isTrue();
                assertThat(send(client, "GET", BASE + "/transfers/jobs/" + jobId, ADMIN, project, null).statusCode())
                        .isEqualTo(200);
                assertThat(keys.findById(keyId).orElseThrow().getLastUsedAt()).isNotNull();
                assertThat(send(client, "DELETE", ACCOUNT + "/" + keyId, VIEWER, project, null).statusCode()).isEqualTo(404);
                assertThat(send(client, "DELETE", ACCOUNT + "/" + keyId, ADMIN, project, null).statusCode()).isEqualTo(204);
                assertThat(send(client, "POST", admin.endpoint, token, project, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")
                        .statusCode()).isEqualTo(401);
                admin.awaitClosed();
            }
            assertThat(send(client, "GET", SSE, token, project, null).statusCode()).isEqualTo(401);
        }
    }

    private StorageConnection fixture(UUID project) throws IOException {
        Path locationPath = Files.createDirectory(root.resolve(project.toString()));
        Files.writeString(locationPath.resolve("film.mov"), "test");
        var location = new StorageConnection();
        location.setName("Mount");
        location.setProjectId(project);
        location.setProviderType(ProviderType.LOCAL);
        location.setRole(StorageRole.SOURCE);
        location.setBasePathOrBucket(locationPath.toString());
        location.setActive(true);
        var saved = connections.save(location);
        for (int days : List.of(1, 20, 40)) {
            var entry = new FileMetadata();
            entry.setConnection(saved);
            entry.setFileName("film-" + days + ".mov");
            entry.setRelativePath(entry.getFileName());
            entry.setSizeBytes(4);
            entry.setLastModifiedAt(Instant.now().minus(days, ChronoUnit.DAYS));
            files.save(entry);
        }
        return saved;
    }

    private JsonNode issue(HttpClient client, String owner, String project) throws Exception {
        var response = send(client, "POST", ACCOUNT, owner, project, "{\"name\":\"Integration test\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return json.readTree(response.body());
    }

    private JsonNode payload(JsonNode result) throws IOException {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        return json.readTree(result.path("content").get(0).path("text").asText());
    }

    private HttpRequest.Builder request(String path, String bearer, String project) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (project != null) builder.header(ProjectScope.HEADER, project);
        return builder;
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String bearer, String project, String body)
            throws Exception {
        var request = request(path, bearer, project);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private ClientSession open(HttpClient client, String token, String project, boolean query) throws Exception {
        var response = client.send(request(query ? SSE + "?token=" + token : SSE, query ? null : token, project)
                .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        return new ClientSession(client, response.body(), token, project);
    }

    private final class ClientSession implements AutoCloseable {
        private final HttpClient client;
        private final BufferedReader stream;
        private final String token;
        private final String project;
        private final String endpoint;
        private int id;

        ClientSession(HttpClient client, InputStream stream, String token, String project) throws Exception {
            this.client = client;
            this.stream = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            this.token = token;
            this.project = project;
            endpoint = next("endpoint");
            assertThat(endpoint).startsWith(BASE + "/mcp/message?sessionId=").doesNotContain(token);
            var initialized = request("initialize", Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "koonj-test", "version", "1.0")));
            assertThat(initialized.path("protocolVersion").asText()).isEqualTo("2024-11-05");
            post(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        }

        JsonNode tools() throws Exception { return request("tools/list", Map.of()).path("tools"); }

        JsonNode call(String name, Map<String, Object> arguments) throws Exception {
            return request("tools/call", Map.of("name", name, "arguments", arguments));
        }

        private JsonNode request(String method, Map<String, Object> params) throws Exception {
            post(Map.of("jsonrpc", "2.0", "id", ++id, "method", method, "params", params));
            var response = json.readTree(next("message"));
            assertThat(response.path("id").asInt()).as(response.toString()).isEqualTo(id);
            assertThat(response.has("error")).as(response.toString()).isFalse();
            return response.path("result");
        }

        private void post(Map<String, Object> request) throws Exception {
            var response = send(client, "POST", endpoint, token, project, json.writeValueAsString(request));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        }

        private String next(String expected) throws IOException {
            for (String line; (line = stream.readLine()) != null; ) {
                if (line.equals("event:" + expected)) {
                    String data = stream.readLine();
                    assertThat(data).startsWith("data:");
                    return data.substring(5);
                }
            }
            throw new IOException("SSE stream closed before " + expected);
        }

        void awaitHeartbeat() throws IOException {
            for (String line; (line = stream.readLine()) != null; ) {
                if (line.equals(":keep-alive")) return;
            }
            throw new IOException("SSE stream closed before its heartbeat");
        }

        void awaitClosed() throws IOException {
            while (stream.readLine() != null) {
                // There may still be heartbeat bytes queued at the time of revocation.
            }
        }

        @Override public void close() throws IOException { stream.close(); }
    }

    @TestConfiguration
    static class TestTokens {
        @Bean @Primary
        JwtDecoder decoder() {
            return value -> {
                if (!ADMIN.equals(value) && !VIEWER.equals(value)) throw new BadJwtException("Invalid fixture token");
                return Jwt.withTokenValue(value).header("alg", "none").subject(value)
                        .claim("realm_access", Map.of("roles", List.of(ADMIN.equals(value) ? "KOONJ_ADMIN" : "KOONJ_VIEWER")))
                        .build();
            };
        }
    }
}
