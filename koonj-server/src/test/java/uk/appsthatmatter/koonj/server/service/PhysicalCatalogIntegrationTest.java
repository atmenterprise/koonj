package uk.appsthatmatter.koonj.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.appsthatmatter.koonj.server.security.ProjectScope;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import uk.appsthatmatter.koonj.server.connector.*;
import uk.appsthatmatter.koonj.server.controller.AdminController;
import uk.appsthatmatter.koonj.server.controller.IntegrationController;
import uk.appsthatmatter.koonj.server.controller.ManagementController;
import uk.appsthatmatter.koonj.server.controller.ManagementController.TemperaturePolicy;
import uk.appsthatmatter.koonj.server.dto.CatalogFavoriteRequest;
import uk.appsthatmatter.koonj.server.dto.CatalogFavoriteResponse;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataValues;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataWriteRequest;
import uk.appsthatmatter.koonj.server.dto.FileResponse;
import uk.appsthatmatter.koonj.server.dto.JobResponse;
import uk.appsthatmatter.koonj.server.dto.StorageUsage;
import uk.appsthatmatter.koonj.server.dto.TransferRequest;
import uk.appsthatmatter.koonj.server.entity.*;
import uk.appsthatmatter.koonj.server.repository.FileMetadataRepository;
import uk.appsthatmatter.koonj.server.repository.StorageConnectionRepository;
import uk.appsthatmatter.koonj.server.repository.RolePermissionSettingsRepository;
import uk.appsthatmatter.koonj.server.repository.TransferJobRepository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static uk.appsthatmatter.koonj.server.entity.TransferJob.Operation.*;
import static uk.appsthatmatter.koonj.server.entity.TransferJob.Status.*;

@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:physical-catalog;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "koonj.transfer.max-parallel-files=2",
        "KOONJ_STORAGE_KEY_PROVIDER=local", "KOONJ_STORAGE_LOCAL_ACTIVE_KEY=integration",
        "KOONJ_STORAGE_LOCAL_KEYS=integration:AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class PhysicalCatalogIntegrationTest {
    @TempDir Path root;
    @Autowired StorageConnectionRepository connections;
    @Autowired FileMetadataRepository files;
    @Autowired CatalogScanService catalog;
    @Autowired FileLineageService lineage;
    @Autowired CatalogMetadataService metadata;
    @Autowired CatalogFavoriteService favorites;
    @Autowired ConnectionService connectionService;
    @Autowired FileTransferService transfers;
    @Autowired JobService jobs;
    @Autowired TransferJobRepository jobRepository;
    @Autowired RolePermissionSettingsRepository roleSettings;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProjectTestTokens serverTokens;
    @Autowired BucketFixture bucketFixture;
    @Autowired AdminController administration;
    @Autowired IntegrationController integrations;
    @Autowired ManagementController management;
    private TestBucket s3;
    private StorageConnection source;
    private StorageConnection bucket;

    @BeforeEach
    void locations() {
        s3 = bucketFixture.connector();
        s3.reset();
        source = connection(ProviderType.LOCAL, root.toString());
        bucket = connection(ProviderType.MINIO, "archive");
    }

    @Test
    void configurableRoleGrantsProtectStorageApisAndPermissionEditing() throws Exception {
        String admin = token("permission-admin", "KOONJ_ADMIN");
        String operator = token("permission-operator", "KOONJ_OPERATOR");
        String viewer = token("permission-viewer", "KOONJ_VIEWER");
        String combined = token("permission-combined", "KOONJ_OPERATOR", "KOONJ_VIEWER");
        String project = ProjectScope.LEGACY.toString();
        for (String account : List.of(admin, operator, viewer, combined)) {
            firstProject(account);
            jdbc.update("insert into project_membership(id, project_id, account_subject, role) values(?,?,?,?)",
                    UUID.randomUUID(), ProjectScope.LEGACY, "oidc:" + account, "MEMBER");
        }

        request(get("/api/v1/connections/locations"), operator, project).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accessKeyOrUsername").doesNotHaveJsonPath());
        request(get("/api/v1/connections/usage"), operator, project).andExpect(status().isOk());
        request(get("/api/v1/connections/{id}/status", source.getId()), operator, project).andExpect(status().isOk());
        request(get("/api/v1/connections"), operator, project).andExpect(status().isForbidden());
        request(post("/api/v1/connections/{id}/test", source.getId()), operator, project).andExpect(status().isForbidden());
        request(post("/api/v1/connections/test").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name", "Draft", "providerType", "LOCAL", "role", "BOTH",
                        "basePathOrBucket", root.toString()))), operator, project).andExpect(status().isForbidden());
        request(get("/api/v1/connections/locations"), viewer, project).andExpect(status().isForbidden());

        request(get("/api/v1/admin/permissions"), admin, project).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].editable").value(false));
        request(get("/api/v1/admin/permissions"), operator, project).andExpect(status().isForbidden());
        request(put("/api/v1/admin/permissions/KOONJ_OPERATOR").contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[]}"), operator, project).andExpect(status().isForbidden());
        request(put("/api/v1/admin/permissions/KOONJ_ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[]}"), admin, project).andExpect(status().isBadRequest());
        request(put("/api/v1/admin/permissions/KOONJ_VIEWER").contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"PERMISSIONS_MANAGE\"]}"), admin, project).andExpect(status().isBadRequest());

        try {
            request(put("/api/v1/admin/permissions/KOONJ_OPERATOR").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"permissions\":[\"CATALOG_OPERATE\"]}"), admin, project).andExpect(status().isOk());
            request(put("/api/v1/admin/permissions/KOONJ_VIEWER").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"permissions\":[\"STORAGE_VIEW\"]}"), admin, project).andExpect(status().isOk());

            request(get("/api/v1/permissions/me"), combined, project).andExpect(jsonPath("$").value(
                    org.hamcrest.Matchers.containsInAnyOrder("CATALOG_OPERATE", "STORAGE_VIEW")));
            request(get("/api/v1/connections/locations"), operator, project).andExpect(status().isForbidden());
            request(get("/api/v1/connections/locations"), viewer, project).andExpect(status().isOk());
            request(get("/api/v1/connections/{id}/status", source.getId()), combined, project).andExpect(status().isOk());
            request(get("/api/v1/catalog/locations"), combined, project).andExpect(status().isForbidden());
            request(get("/api/v1/transfers/jobs"), combined, project).andExpect(status().isForbidden());
        } finally {
            roleSettings.deleteById("KOONJ_OPERATOR");
            roleSettings.deleteById("KOONJ_VIEWER");
        }
    }

    @Test
    void signedInUsersSwitchOnlyBetweenTheirAssignedProjects() throws Exception {
        String admin = token("project-admin", "KOONJ_ADMIN");
        String operator = token("project-operator", "KOONJ_OPERATOR");
        String pending = token("project-pending");
        assertThat(firstProject(admin)).isNotEqualTo(ProjectScope.LEGACY.toString());
        String personal = firstProject(operator);
        assertThat(personal).isNotEqualTo(ProjectScope.LEGACY.toString());
        firstProject(pending);
        request(get("/api/v1/catalog/locations"), pending, null).andExpect(status().isForbidden());
        request(get("/api/v1/projects/users"), operator, null).andExpect(status().isNotFound());

        var created = request(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Research\"}"), admin, null).andExpect(status().isCreated()).andReturn();
        String shared = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        request(get("/api/v1/catalog/locations"), operator, shared).andExpect(status().isForbidden());
        request(get("/api/v1/catalog/locations"), admin, personal).andExpect(status().isForbidden());
        request(get("/api/v1/projects/{id}/members", shared), operator, shared).andExpect(status().isForbidden());
        selectEmailChannel(admin, shared);
        request(post("/api/v1/projects/{id}/invitations", shared).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"project-operator@example.test\"}"), admin, null).andExpect(status().isAccepted());
        var invite = jdbc.queryForObject("select id from project_invitation where project_id=?", UUID.class, UUID.fromString(shared));
        String accept = "/api/v1/projects/invitations/accept";
        String secret = UUID.randomUUID().toString();
        String body = json.writeValueAsString(Map.of("token", secret));
        jdbc.update("update project_invitation set delivery='SENT', token_digest=? where id=?",
                uk.appsthatmatter.koonj.notification.ProjectInvitation.digest(secret), invite);
        request(post(accept).contentType(MediaType.APPLICATION_JSON).content(body), pending, null).andExpect(status().isForbidden());
        request(post(accept).contentType(MediaType.APPLICATION_JSON).content(body), operator, null).andExpect(status().isOk());
        request(post(accept).contentType(MediaType.APPLICATION_JSON).content(body), operator, null).andExpect(status().isForbidden());
        request(post("/api/v1/projects/session"), operator, null).andExpect(jsonPath("$[*].id").value(
                org.hamcrest.Matchers.containsInAnyOrder(personal, shared)));
        request(delete("/api/v1/projects/{id}/members", shared).param("subject", "oidc:project-admin"), operator, null)
                .andExpect(status().isForbidden());

        var sharedFolder = Files.createDirectory(root.resolve("research"));
        Files.writeString(sharedFolder.resolve("report.txt"), "project-scoped data");
        var location = connection(ProviderType.LOCAL, sharedFolder.toString());
        location.setProjectId(UUID.fromString(shared));
        connections.saveAndFlush(location);

        request(get("/api/v1/catalog/locations"), operator, shared)
                .andExpect(jsonPath("$[0].id").value(location.getId().toString()));
        request(get("/api/v1/catalog/locations"), operator, personal)
                .andExpect(jsonPath("$").isEmpty());
        request(post("/api/v1/catalog/scan/{id}", source.getId()), operator, shared).andExpect(status().isNotFound());
        var submitted = request(post("/api/v1/catalog/scan/sources"), admin, shared).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobs.length()").value(1)).andReturn();
        String job = json.readTree(submitted.getResponse().getContentAsString()).get("jobs").get(0).get("id").asText();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                request(get("/api/v1/transfers/jobs/{id}", job), admin, shared)
                        .andExpect(jsonPath("$.status").value("COMPLETED")));
        request(get("/api/v1/transfers/jobs/{id}", job), operator, shared).andExpect(status().isNotFound());
        request(get("/api/v1/transfers/jobs/{id}/events", job), operator, shared).andExpect(status().isNotFound());
        request(get("/api/v1/transfers/jobs/{id}/files", job), operator, shared).andExpect(status().isNotFound());
        request(post("/api/v1/transfers/jobs/{id}/control", job).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"STOP\"}"), operator, shared).andExpect(status().isNotFound());
        request(get("/api/v1/transfers/jobs"), operator, shared).andExpect(jsonPath("$.totalElements").value(0));
        request(get("/api/v1/transfers/jobs"), admin, shared).andExpect(jsonPath("$.content[0].id").value(job));
        request(get("/api/v1/transfers/jobs"), operator, personal).andExpect(jsonPath("$.content").isEmpty());
        var file = files.findByConnectionIdAndRelativePath(location.getId(), "report.txt").orElseThrow();
        request(get("/api/v1/catalog/files/{id}", file.getId()), operator, shared).andExpect(status().isOk());
        request(get("/api/v1/catalog/files/{id}", file.getId()), operator, personal).andExpect(status().isNotFound());

        String agent = "project-fuse-agent";
        serverTokens.tokens.put(agent, Jwt.withTokenValue(agent).header("alg", "RS256").subject(agent)
                .claim("azp", "koonj-agent").claim("realm_access", Map.of("roles", List.of("KOONJ_OPERATOR"))).build());
        request(get("/api/v1/catalog/files/{id}", file.getId()), agent, shared).andExpect(status().isForbidden());
        request(get("/api/v1/catalog/files/{id}", file.getId()), agent, null).andExpect(status().isNotFound());
        request(get("/api/v1/catalog/locations"), agent, null).andExpect(status().isForbidden());

        var projectBucket = connection(ProviderType.MINIO, "research-archive");
        projectBucket.setProjectId(UUID.fromString(shared));
        projectBucket.setRole(StorageRole.TARGET);
        connections.saveAndFlush(projectBucket);
        var crossProjectArchive = new TransferRequest(location.getId(), bucket.getId(),
                List.of("report.txt"), null, "STANDARD", ArchiveMode.COPY, false);
        request(post("/api/v1/transfers/archive").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(crossProjectArchive)), operator, shared).andExpect(status().isNotFound());
        var projectArchive = new TransferRequest(location.getId(), projectBucket.getId(),
                List.of("report.txt"), null, "STANDARD", ArchiveMode.COPY, false);
        var archive = request(post("/api/v1/transfers/archive").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(projectArchive)), operator, shared).andExpect(status().isAccepted()).andReturn();
        String archiveId = json.readTree(archive.getResponse().getContentAsString()).get("id").asText();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                request(get("/api/v1/transfers/jobs/{id}", archiveId), operator, shared)
                        .andExpect(jsonPath("$.status").value("COMPLETED")));
        request(get("/api/v1/transfers/jobs/{id}/files", archiveId), operator, shared)
                .andExpect(jsonPath("$.content.length()").value(1));

        String renamedToken = "renewed-operator-token";
        serverTokens.tokens.put(renamedToken, Jwt.withTokenValue(renamedToken).header("alg", "RS256")
                .subject(operator).claim("preferred_username", "renamed-operator")
                .claim("realm_access", Map.of("roles", List.of("KOONJ_OPERATOR"))).build());
        request(get("/api/v1/transfers/jobs"), renamedToken, shared)
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(archiveId));
        request(get("/api/v1/transfers/jobs/{id}/files", archiveId), admin, shared).andExpect(status().isNotFound());

        request(put("/api/v1/management/temperature-policy").contentType(MediaType.APPLICATION_JSON)
                .content("{\"hotDays\":8,\"coldDays\":70}"), admin, shared).andExpect(status().isOk());
        request(get("/api/v1/management/temperature-policy"), operator, shared).andExpect(jsonPath("$.coldDays").value(70));
        request(get("/api/v1/management/temperature-policy"), operator, personal).andExpect(jsonPath("$.coldDays").value(30));

        request(delete("/api/v1/projects/{id}/members", shared).param("subject", "oidc:project-operator"), admin, null)
                .andExpect(status().isNoContent());
        request(get("/api/v1/catalog/locations"), operator, shared).andExpect(status().isForbidden());
        request(delete("/api/v1/projects/{id}/members", personal).param("subject", "oidc:project-operator"), operator, null)
                .andExpect(status().isConflict());
    }

    @Test
    void concurrentFirstLoginAndInvitationLifecycle() throws Exception {
        String alice = token("lifecycle-alice");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var results = pool.invokeAll(java.util.Collections.<java.util.concurrent.Callable<String>>nCopies(4, () -> firstProject(alice)));
            for (var result : results) assertThat(result.get()).isEqualTo(results.getFirst().get());
        }
        String project = firstProject(alice);
        String bob = token("lifecycle-bob");
        String path = "/api/v1/projects/" + project + "/invitations";
        String email = "{\"email\":\"lifecycle-bob@example.test\"}";
        request(post(path).contentType(MediaType.APPLICATION_JSON).content(email), alice, null).andExpect(status().isConflict());
        selectEmailChannel(alice, project);
        for (int i = 0; i < 2; i++) request(post(path).contentType(MediaType.APPLICATION_JSON).content(email), alice, null)
                .andExpect(status().isAccepted());
        var listing = request(get(path), alice, null).andExpect(jsonPath("$.length()").value(1)).andReturn();
        String id = json.readTree(listing.getResponse().getContentAsString()).get(0).get("id").asText();
        String secret = UUID.randomUUID().toString();
        var digest = uk.appsthatmatter.koonj.notification.ProjectInvitation.digest(secret);
        jdbc.update("update project_invitation set token_digest=?, expires_at=? where id=?", digest,
                java.sql.Timestamp.from(Instant.now().minusSeconds(10)), UUID.fromString(id));
        String accept = "/api/v1/projects/invitations/accept";
        String body = json.writeValueAsString(Map.of("token", secret));
        request(post(accept).contentType(MediaType.APPLICATION_JSON).content(body), bob, null).andExpect(status().isForbidden());
        request(post(path + "/" + id + "/resend"), alice, null).andExpect(status().isAccepted());
        request(post(accept).contentType(MediaType.APPLICATION_JSON).content(body), bob, null).andExpect(status().isForbidden());
        jdbc.update("update project_invitation set token_digest=? where id=?", digest, UUID.fromString(id));
        String unverified = "lifecycle-unverified";
        serverTokens.tokens.put(unverified, Jwt.withTokenValue(unverified).header("alg", "RS256").subject(unverified)
                .claim("email", bob + "@example.test").claim("email_verified", false).build());
        request(post(accept).contentType(MediaType.APPLICATION_JSON).content(body), unverified, null).andExpect(status().isForbidden());
        request(delete(path + "/" + id), alice, null).andExpect(status().isNoContent());
        request(post(accept).contentType(MediaType.APPLICATION_JSON).content(body), bob, null).andExpect(status().isForbidden());
        request(get(path), bob, project).andExpect(status().isForbidden());
        request(get("/api/v1/projects"), bob, null).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].role").value("ADMIN"))
                .andExpect(jsonPath("$[0].id").value(org.hamcrest.Matchers.not(project)));
    }

    @Autowired uk.appsthatmatter.koonj.notification.NotificationChannelRepository notificationChannels;

    private void selectEmailChannel(String admin, String project) throws Exception {
        var channel = new uk.appsthatmatter.koonj.notification.NotificationChannel();
        channel.setName("Project mail");
        channel.setType(uk.appsthatmatter.koonj.notification.NotificationChannel.Type.EMAIL);
        channel.setEnabled(true);
        channel.setConfig(new uk.appsthatmatter.koonj.notification.NotificationChannel.Config(null, null, null, null,
                "mail.example.test", 587, "STARTTLS", null, null, "sender@example.test", null, null));
        var saved = notificationChannels.saveAndFlush(channel);
        request(put("/api/v1/projects/{id}/invitation-channel", project).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("channelId", saved.getId()))), admin, null).andExpect(status().isNoContent());
    }

    private String token(String subject, String... roles) {
        var token = Jwt.withTokenValue(subject).header("alg", "RS256").subject(subject).claim("name", "Alex").claim("email_verified", true).claim("email", subject + "@example.test")
                .claim("realm_access", Map.of("roles", List.of(roles))).build();
        serverTokens.tokens.put(subject, token);
        return subject;
    }

    private String firstProject(String token) throws Exception {
        var result = request(post("/api/v1/projects/session"), token, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get(0).get("id").asText();
    }

    private ResultActions request(MockHttpServletRequestBuilder request, String token, String project) throws Exception {
        if (project != null) request.header(ProjectScope.HEADER, project);
        return mvc.perform(request.header("Authorization", "Bearer " + token));
    }

    @Test
    void administrationAndManagementEnforceAccessAndPersistSettings() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("viewer", null,
                List.of(new SimpleGrantedAuthority("ROLE_KOONJ_VIEWER"))));
        SecurityContextHolder.setContext(context);
        var policy = new TemperaturePolicy(10, 45);
        try {
            assertThat(management.temperaturePolicy()).isEqualTo(new TemperaturePolicy(15, 30));
            assertThatThrownBy(() -> management.updateTemperaturePolicy(policy)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> integrations.list(IntegrationEndpoint.Kind.MCP)).isInstanceOf(AccessDeniedException.class);
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("admin", null,
                    List.of(new SimpleGrantedAuthority("ROLE_KOONJ_ADMIN"))));
            assertThat(administration.telemetry().components()).containsEntry("db", "UP");
            assertThat(administration.observability().exporters()).containsKeys("logs", "metrics", "traces");
            assertThatThrownBy(() -> management.updateTemperaturePolicy(new TemperaturePolicy(40, 30)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(management.updateTemperaturePolicy(policy)).isEqualTo(policy);
            assertThat(management.temperaturePolicy()).isEqualTo(policy);
            var settings = new IntegrationController.Settings(IntegrationEndpoint.Kind.MCP, "Catalog MCP",
                    "https://catalog.example.com/mcp", true);
            var endpoint = integrations.create(settings);
            assertThat(integrations.list(IntegrationEndpoint.Kind.MCP)).contains(endpoint);
            var updated = integrations.update(endpoint.id(), new IntegrationController.Settings(settings.kind(), settings.name(), settings.url(), false));
            assertThat(updated.enabled()).isFalse();
            assertThatThrownBy(() -> integrations.create(new IntegrationController.Settings(
                    IntegrationEndpoint.Kind.API, "invalid", "https://user:password@example.com/api", true)))
                    .isInstanceOf(IllegalArgumentException.class);
            integrations.delete(endpoint.id());
            assertThat(integrations.list(IntegrationEndpoint.Kind.MCP)).doesNotContain(endpoint, updated);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void queuesEveryActiveSourceOnceAndLeavesTargetOnlyAndInactiveConnectionsOut() throws Exception {
        var previouslyActive = connections.findAll().stream().filter(StorageConnection::isActive)
                .filter(connection -> !connection.getId().equals(source.getId()) && !connection.getId().equals(bucket.getId()))
                .toList();
        previouslyActive.forEach(connection -> connection.setActive(false));
        connections.saveAllAndFlush(previouslyActive);
        source.setRole(StorageRole.SOURCE);
        bucket.setRole(StorageRole.TARGET);
        connections.saveAllAndFlush(List.of(source, bucket));
        var second = connection(ProviderType.LOCAL, Files.createDirectory(root.resolve("second")).toString());
        Files.writeString(Path.of(second.getBasePathOrBucket()).resolve("file"), "scanned contents");
        var inactive = connection(ProviderType.LOCAL, root.toString());
        inactive.setActive(false);
        connections.saveAndFlush(inactive);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocker = jobs.create(SCAN, source.getId(), null, List.of());
        var running = jobs.execute(blocker, job -> { entered.countDown(); release.await(); });
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var batch = catalog.submitSources();
            assertThat(batch.existingScanCount()).isEqualTo(1);
            assertThat(batch.jobs()).singleElement().satisfies(job -> assertThat(job.sourceConnectionId()).isEqualTo(second.getId()));
            var repeat = catalog.submitSources();
            assertThat(repeat.jobs()).isEmpty();
            assertThat(repeat.existingScanCount()).isEqualTo(2);
            assertThatThrownBy(() -> catalog.submit(second.getId()))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("409");
            release.countDown();
            completed(batch.jobs().getFirst());
            assertThat(files.findByConnectionIdAndRelativePath(second.getId(), "file")).isPresent();
        } finally {
            release.countDown();
            running.get(10, TimeUnit.SECONDS);
            previouslyActive.forEach(connection -> connection.setActive(true));
            connections.saveAllAndFlush(previouslyActive);
        }
    }

    @Test
    void testsSubmittedConnectionSettingsWithoutRegisteringAConnection() throws Exception {
        String admin = token("draft-connection-admin", "KOONJ_ADMIN");
        String project = firstProject(admin);
        long registered = connections.count();

        request(post("/api/v1/connections/test").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name", "Draft", "providerType", "LOCAL", "role", "BOTH",
                        "basePathOrBucket", root.toString()))), admin, project)
                .andExpect(status().isOk()).andExpect(jsonPath("$.healthy").value(true));
        request(post("/api/v1/connections/test").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name", "Draft", "providerType", "LOCAL", "role", "BOTH",
                        "basePathOrBucket", root.resolve("missing").toString()))), admin, project)
                .andExpect(status().isOk()).andExpect(jsonPath("$.healthy").value(false));

        assertThat(connections.count()).isEqualTo(registered);
        assertThat(root).isEmptyDirectory();
    }

    @Test
    void rejectsSubmittedCredentialsAndOmitsLegacyFieldsFromResponses() throws Exception {
        String admin = token("storage-auth-admin", "KOONJ_ADMIN");
        String project = firstProject(admin);
        var draft = new java.util.HashMap<String, String>(Map.of("name", "Host identity", "providerType", "SMB",
                "role", "SOURCE", "basePathOrBucket", root.toString()));
        long registered = connections.count();

        draft.put("secretKeyOrPassword", "rejected-fixture");
        request(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(draft)), admin, project)
                .andExpect(status().isBadRequest());
        request(put("/api/v1/connections/" + source.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(draft)), admin, project)
                .andExpect(status().isBadRequest());
        assertThat(connections.count()).isEqualTo(registered);

        draft.remove("secretKeyOrPassword");
        request(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(draft)), admin, project)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authMode").value("HOST_IDENTITY"))
                .andExpect(jsonPath("$.accessKeyOrUsername").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.secretKeyOrPassword").doesNotHaveJsonPath());

        draft.put("providerType", "AWS_S3");
        draft.put("basePathOrBucket", "aws-fixture-bucket");
        draft.put("endpointUrl", "https://different.example");
        request(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(draft)), admin, project)
                .andExpect(status().isBadRequest());
    }

    @Test
    void savesOnlyEncryptedCredentialsAndSerializesRotation() throws Exception {
        String admin = token("storage-encryption-admin", "KOONJ_ADMIN");
        String project = firstProject(admin);
        var input = new java.util.HashMap<String, Object>(Map.of("name", "Encrypted storage fixture",
                "providerType", "MINIO", "role", "SOURCE", "basePathOrBucket", "archive",
                "endpointUrl", "https://objects.example.test", "authMode", "ENCRYPTED_CREDENTIALS",
                "credentials", Map.of("accessKeyId", "fixture-access", "secretAccessKey", "fixture-secret-not-returned")));
        var creation = request(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(input)), admin, project).andExpect(status().isCreated())
                .andExpect(jsonPath("$.credentials").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.credentialEnvelope").doesNotHaveJsonPath()).andReturn();
        String response = creation.getResponse().getContentAsString();
        assertThat(response).doesNotContain("fixture-access", "fixture-secret-not-returned");
        UUID id = UUID.fromString(json.readTree(response).path("id").asText());
        var projectCreation = request(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Encrypted storage isolation fixture\"}"), admin, project)
                .andExpect(status().isCreated()).andReturn();
        String otherProject = json.readTree(projectCreation.getResponse().getContentAsString()).path("id").asText();
        request(put("/api/v1/connections/{id}/credentials", id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("accessKeyId", "other-project", "secretAccessKey", "denied-fixture"))),
                admin, otherProject).andExpect(status().isNotFound());

        byte[] first = connections.findByIdAndProjectId(id, UUID.fromString(project)).orElseThrow().getCredentialEnvelope();
        assertThat(new String(first, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("fixture-access", "fixture-secret-not-returned");

        request(put("/api/v1/connections/{id}/credentials", id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("accessKeyId", "invalid-fixture", "secretAccessKey", "failed-fixture"))),
                admin, project).andExpect(status().isBadGateway());
        assertThat(connections.findByIdAndProjectId(id, UUID.fromString(project)).orElseThrow().getCredentialEnvelope()).isEqualTo(first);

        request(put("/api/v1/connections/{id}/credentials", id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("accessKeyId", "replacement-fixture", "secretAccessKey", "replacement-secret"))),
                admin, project).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
        byte[] rotated = connections.findByIdAndProjectId(id, UUID.fromString(project)).orElseThrow().getCredentialEnvelope();
        assertThat(rotated).isNotEqualTo(first);
        input.remove("credentials");
        input.put("name", "Renamed encrypted fixture");
        request(put("/api/v1/connections/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(input)), admin, project).andExpect(status().isOk());
        assertThat(connections.findByIdAndProjectId(id, UUID.fromString(project)).orElseThrow().getCredentialEnvelope()).isEqualTo(rotated);
        input.put("endpointUrl", "https://different.example.test");
        request(put("/api/v1/connections/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(input)), admin, project).andExpect(status().isBadRequest());
    }

    @Test
    void checksActiveStorageWithoutWritingAndSkipsInactiveLocations() throws IOException {
        var baseline = FileTime.from(Instant.parse("2025-01-01T00:00:00Z"));
        Files.setLastModifiedTime(root, baseline);
        var mounted = connectionService.connectivity(source.getId());
        assertThat(mounted.healthy()).isTrue();
        assertThat(mounted.capacity().totalBytes()).isGreaterThan(0);
        assertThat(mounted.capacity().availableBytes()).isBetween(0L, mounted.capacity().totalBytes());
        assertThat(Files.getLastModifiedTime(root)).isEqualTo(baseline);
        assertThat(connectionService.connectivity(bucket.getId()).healthy()).isTrue();
        assertThat(connectionService.connectivity(bucket.getId()).capacity()).isNull();

        var missing = connection(ProviderType.LOCAL, root.resolve("missing").toString());
        var offline = connectionService.connectivity(missing.getId());
        assertThat(offline.healthy()).isFalse();
        assertThat(offline.message()).contains("path not found");
        missing.setActive(false);
        connections.saveAndFlush(missing);
        assertThat(connectionService.connectivity(missing.getId()).message()).isEqualTo("Connection is inactive");
    }

    @Test
    void summarizesMultipleStorageProvidersAndAttributesSavingsToBothEnds() throws IOException {
        StorageConnection secondTarget = connection(ProviderType.RUSTFS, "alternate");
        Files.writeString(root.resolve("copy"), "keep");
        Files.writeString(root.resolve("purge"), "release");
        completed(transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                List.of("copy"), null, null, ArchiveMode.COPY, false)));
        completed(transfers.submit(ARCHIVE, new TransferRequest(source.getId(), secondTarget.getId(),
                List.of("purge"), null, null, ArchiveMode.PURGE, false)));

        StorageUsage usage = connectionService.storageUsage();
        assertThat(usage.footprints()).filteredOn(item -> List.of(source.getId(), bucket.getId(), secondTarget.getId())
                        .contains(item.connectionId()))
                .extracting(StorageUsage.Footprint::connectionId, StorageUsage.Footprint::fileCount, StorageUsage.Footprint::sizeBytes)
                .containsExactlyInAnyOrder(tuple(source.getId(), 1L, 4L), tuple(bucket.getId(), 1L, 4L), tuple(secondTarget.getId(), 1L, 7L));
        assertThat(usage.reclaimed()).filteredOn(item -> item.sourceConnectionId().equals(source.getId()))
                .containsExactly(new StorageUsage.Reclaimed(source.getId(), secondTarget.getId(), 1, 7));
    }

    @Test
    void keepsFavoritesPrivateAndCleansThemUpWhenTheirConnectionIsDeleted() {
        var folder = CatalogMetadataWriteRequest.Scope.FOLDER;
        var file = CatalogMetadataWriteRequest.Scope.FILE;
        var selectedFolder = new CatalogFavoriteRequest(source.getId(), folder, "docs/", true);
        favorites.setFavorite("alice", selectedFolder);
        favorites.setFavorite("alice", new CatalogFavoriteRequest(source.getId(), folder, "docs", true));
        favorites.setFavorite("alice", new CatalogFavoriteRequest(source.getId(), file, "docs/report.txt", true));
        favorites.setFavorite("bob", selectedFolder);

        assertThat(favorites.list("alice")).extracting(CatalogFavoriteResponse::path)
                .containsExactlyInAnyOrder("docs", "docs/report.txt");
        assertThat(favorites.list("bob")).extracting(CatalogFavoriteResponse::path).containsExactly("docs");

        favorites.setFavorite("alice", new CatalogFavoriteRequest(source.getId(), folder, "docs/", false));
        assertThat(favorites.list("alice")).extracting(CatalogFavoriteResponse::path).containsExactly("docs/report.txt");
        assertThat(favorites.list("bob")).hasSize(1);
        connectionService.delete(source.getId());
        assertThat(favorites.list("alice")).isEmpty();
        assertThat(favorites.list("bob")).isEmpty();
    }

    @Test
    void readsFileAttributesOnDemandWithoutInferringTheAuthorFromTheOwner() throws IOException {
        Path report = Files.writeString(root.resolve("report"), "contents");
        completed(catalog.submit(source.getId()));

        var attributes = catalog.attributes(entry(source, "report").id());
        assertThat(attributes.owner()).isEqualTo(Files.getOwner(report).getName());
        assertThat(attributes.createdAt()).isNotNull();
        assertThat(attributes.lastAccessedAt()).isNotNull();
        assertThat(attributes.author()).isNull();
        assertThat(attributes.lastAccessedBy()).isNull();
    }

    @Test
    void savesFileAndFolderDescriptionsAndPreservesThemWhenOnlyOtherMetadataChanges() {
        var folder = new CatalogMetadataWriteRequest.Target(CatalogMetadataWriteRequest.Scope.FOLDER, "docs/");
        var file = new CatalogMetadataWriteRequest.Target(CatalogMetadataWriteRequest.Scope.FILE, "docs/report");
        var replace = CatalogMetadataWriteRequest.Mode.REPLACE;
        metadata.save(source.getId(), new CatalogMetadataWriteRequest(List.of(folder),
                new CatalogMetadataValues(List.of(), List.of(), "Reports\nand supporting files"), replace));
        metadata.save(source.getId(), new CatalogMetadataWriteRequest(List.of(file),
                new CatalogMetadataValues(List.of("finance"), List.of(), "Quarterly report"), replace));
        metadata.save(source.getId(), new CatalogMetadataWriteRequest(List.of(file),
                new CatalogMetadataValues(List.of("reviewed"), List.of()), replace));

        assertThat(metadata.list(source.getId())).satisfiesExactly(
                item -> assertThat(item.values().description()).isEqualTo("Reports\nand supporting files"),
                item -> {
                    assertThat(item.values().description()).isEqualTo("Quarterly report");
                    assertThat(item.values().tags()).containsExactly("reviewed");
                });

        metadata.save(source.getId(), new CatalogMetadataWriteRequest(List.of(folder),
                new CatalogMetadataValues(List.of(), List.of(), ""), replace));
        assertThat(metadata.list(source.getId())).extracting(item -> item.values().description()).containsExactly("Quarterly report");
    }

    @Test
    void scansPhysicalEntriesAndPrunesOnlyTheScannedLocation() throws IOException {
        Path gone = Files.writeString(root.resolve("gone.txt"), "gone soon");
        Files.createDirectory(root.resolve("empty"));
        Path link = Files.createSymbolicLink(root.resolve("link"), Path.of("missing"));
        var other = connection(ProviderType.SMB, Files.createDirectory(root.resolve("share")).toString());
        Files.writeString(root.resolve("share/gone.txt"), "still present on the share");

        completed(catalog.submit(source.getId()));
        completed(catalog.submit(other.getId()));
        assertThat(inventory(source)).extracting(FileResponse::entryType)
                .contains(EntryType.FILE, EntryType.DIRECTORY, EntryType.SYMLINK);
        assertThat(entry(source, "link").symlinkTarget()).endsWith("/missing");

        Files.delete(gone);
        Files.delete(link);
        Files.writeString(link, "now a file");
        completed(catalog.submit(source.getId()));
        assertThat(files.findByConnectionIdAndRelativePath(source.getId(), "gone.txt")).isEmpty();
        assertThat(entry(source, "link").entryType()).isEqualTo(EntryType.FILE);
        assertThat(entry(source, "link").symlinkTarget()).isNull();
        assertThat(entry(other, "gone.txt").entryType()).isEqualTo(EntryType.FILE);

        s3.put("external", new byte[]{1});
        completed(catalog.submit(bucket.getId()));
        assertThat(entry(bucket, "external").entryType()).isEqualTo(EntryType.FILE);
        s3.deleteFile(bucket, "external");
        completed(catalog.submit(bucket.getId()));
        assertThat(inventory(bucket)).isEmpty();
    }

    @Test
    void copyPublishesOnlyAfterVerificationAndKeepsLocalFile() throws IOException {
        Path original = Files.writeString(root.resolve("report"), "original bytes");
        completed(catalog.submit(source.getId()));
        var gate = s3.blockRead(StoragePaths.ARCHIVE_STAGING_PREFIX);
        var archive = transfers.submit(ARCHIVE, request(source, bucket, null, false));
        try {
            gate.awaitRead();
            assertThat(jobs.get(archive.id()).status()).isEqualTo(RUNNING);
            assertThat(archive.archiveMode()).isEqualTo(ArchiveMode.COPY);
            assertThat(inventory(bucket)).isEmpty();
            assertThat(s3.objects).doesNotContainKey("report");
            assertThat(entry(source, "report").entryType()).isEqualTo(EntryType.FILE);
        } finally {
            gate.release.countDown();
        }
        completed(archive);
        assertThat(original).hasContent("original bytes");
        assertThat(entry(source, "report").storageState()).isEqualTo(StorageState.ARCHIVED_COPY);
        assertThat(entry(bucket, "report").storageState()).isEqualTo(StorageState.ARCHIVED);
        assertThat(entry(bucket, "report").entryType()).isEqualTo(EntryType.FILE);
        assertThat(entry(bucket, "report").id()).isNotEqualTo(entry(source, "report").id());
        assertThat(s3.objects.keySet()).containsExactly("report");

        completed(catalog.submit(source.getId()));
        completed(catalog.submit(bucket.getId()));
        assertThat(entry(source, "report").storageState()).isEqualTo(StorageState.ARCHIVED_COPY);
        assertThat(entry(bucket, "report").checksumSha256()).isNotBlank();
        s3.deleteFile(bucket, "report");
        completed(catalog.submit(bucket.getId()));
        assertThat(inventory(bucket)).isEmpty();
        assertThat(entry(source, "report").storageState()).isEqualTo(StorageState.LOCAL_ONLY);
    }

    @Test
    void purgeCreatesSymlinkAndRecallKeepsItVisibleUntilVerifiedThenRestoresFile() throws IOException {
        Path original = Files.writeString(root.resolve("report"), "original bytes");
        completed(transfers.submit(ARCHIVE, request(source, bucket, ArchiveMode.PURGE, false)));
        assertThat(Files.isSymbolicLink(original)).isTrue();
        assertThat(entry(source, "report").storageState()).isEqualTo(StorageState.ARCHIVED_SYMLINK);
        UUID linkId = entry(source, "report").id();
        assertThat(entry(source, "report").symlinkTarget()).isEqualTo("koonj-ref://" + linkId);
        assertThat(root.toFile().list()).containsExactly("report");
        assertThat(entry(source, "report").sizeBytes()).isEqualTo(14);
        assertThat(entry(bucket, "report").entryType()).isEqualTo(EntryType.FILE);
        completed(catalog.submit(source.getId()));
        assertThat(catalog.get(linkId).sizeBytes()).isEqualTo(14);
        assertThat(catalog.get(linkId).checksumSha256()).isNotBlank();

        var gate = s3.blockRead("report");
        var recall = transfers.submit(RECALL, new TransferRequest(bucket.getId(), source.getId(),
                null, List.of(linkId), null, null, false));
        try {
            gate.awaitRead();
            assertThat(jobs.get(recall.id()).status()).isEqualTo(RUNNING);
            assertThat(Files.isSymbolicLink(original)).isTrue();
            assertThat(entry(source, "report").entryType()).isEqualTo(EntryType.SYMLINK);
            assertThat(entry(bucket, "report").entryType()).isEqualTo(EntryType.FILE);
        } finally {
            gate.release.countDown();
        }
        completed(recall);
        assertThat(Files.isSymbolicLink(original)).isFalse();
        assertThat(original).hasContent("original bytes");
        assertThat(entry(source, "report").entryType()).isEqualTo(EntryType.FILE);
        assertThat(entry(source, "report").storageState()).isEqualTo(StorageState.LOCAL_ONLY);
        assertThat(entry(source, "report").symlinkTarget()).isNull();
        assertThat(entry(bucket, "report").entryType()).isEqualTo(EntryType.FILE);

        completed(transfers.submit(RECALL, request(bucket, source, null, true)));
        assertThat(inventory(bucket)).isEmpty();
        assertThat(s3.objects).doesNotContainKey("report");
    }

    @Test
    void synchronousRecallReturnsColdEstimateThenWaitsForVerifiedReplacement() throws Exception {
        Path original = Files.writeString(root.resolve("report"), "original bytes");
        completed(transfers.submit(ARCHIVE, request(source, bucket, ArchiveMode.PURGE, false)));
        UUID fileId = entry(source, "report").id();
        Instant estimate = Instant.now().plus(Duration.ofHours(5)).truncatedTo(ChronoUnit.MICROS).plusNanos(500);
        s3.coldReady = estimate;
        var coldRecall = transfers.recallSync(fileId, 5);
        assertThat(coldRecall.status()).isEqualTo("RESTORING_GLACIER");
        assertThat(coldRecall.estimatedReadyAt()).isCloseTo(estimate, within(1, ChronoUnit.MICROS));
        assertThat(Files.isSymbolicLink(original)).isTrue();

        s3.coldReady = null;
        var gate = s3.blockRead("report");
        var recall = CompletableFuture.supplyAsync(() -> transfers.recallSync(fileId, 10));
        try {
            gate.awaitRead();
            assertThat(recall).isNotDone();
            assertThat(Files.isSymbolicLink(original)).isTrue();
        } finally {
            gate.release.countDown();
        }
        assertThat(recall.get(10, TimeUnit.SECONDS).status()).isEqualTo("RECALLED");
        assertThat(original).hasContent("original bytes");
        assertThat(catalog.get(fileId).storageState()).isEqualTo(StorageState.LOCAL_ONLY);
        assertThat(jobRepository.findAll()).anySatisfy(job -> {
            assertThat(job.getTargetConnectionId()).isEqualTo(source.getId());
            assertThat(job.getPriority()).isEqualTo(100);
            assertThat(job.getStatus()).isEqualTo(COMPLETED);
        });
    }

    @Test
    void pausesAfterBothInFlightFilesAndResumesTheSameArchive() throws Exception {
        Files.createDirectory(root.resolve("docs"));
        Files.writeString(root.resolve("docs/first.txt"), "first file");
        Files.writeString(root.resolve("docs/second.txt"), "second file");
        Files.writeString(root.resolve("docs/third.txt"), "third file");
        var gate = s3.blockRead(StoragePaths.ARCHIVE_STAGING_PREFIX, 2);
        var job = transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                List.of("docs/"), null, null, ArchiveMode.COPY, false));
        List<String> started;
        try {
            gate.awaitRead();
            var activities = jobs.activeArchives(source.getId());
            assertThat(activities).hasSize(1);
            started = activities.getFirst().currentFiles();
            assertThat(started).hasSize(2);
            assertThat(jobs.control(job.id(), JobService.Action.PAUSE, true).getStatus()).isEqualTo(PAUSING);
        } finally {
            gate.release.countDown();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jobs.get(job.id()).status()).isEqualTo(PAUSED));
        assertThat(jobs.get(job.id()).completedAt()).isNull();
        assertThat(jobs.get(job.id()).processedFiles()).isEqualTo(2);
        assertThat(s3.objects.keySet()).containsExactlyInAnyOrderElementsOf(started);
        assertThat(jobs.activeArchives(source.getId())).isEmpty();
        transfers.execute(jobs.control(job.id(), JobService.Action.RESUME, true));
        completed(job);
        assertThat(jobs.get(job.id()).processedFiles()).isEqualTo(3);
        assertThat(jobs.fileResults(job.id(), false, PageRequest.of(0, 10)).content()).hasSize(3);
    }

    @Test
    void restartsAStoppedPurgeWithoutReprocessingVerifiedFiles() throws Exception {
        Path first = Files.writeString(root.resolve("first.txt"), "first");
        Path second = Files.writeString(root.resolve("second.txt"), "second");
        Path third = Files.writeString(root.resolve("third.txt"), "third");
        var gate = s3.blockRead(StoragePaths.ARCHIVE_STAGING_PREFIX, 2);
        var job = transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                List.of("first.txt", "second.txt", "third.txt"), null, null, ArchiveMode.PURGE, false));
        try {
            gate.awaitRead();
            assertThat(jobs.control(job.id(), JobService.Action.STOP, true).getStatus()).isEqualTo(STOPPING);
        } finally {
            gate.release.countDown();
        }
        assertThat(finished(job).status()).isEqualTo(STOPPED);
        assertThat(Files.isSymbolicLink(first)).isTrue();
        assertThat(Files.isSymbolicLink(second)).isTrue();
        assertThat(Files.isSymbolicLink(third)).isFalse();
        assertThatThrownBy(() -> jobs.control(job.id(), JobService.Action.RESTART, false))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("requires the purge permission");
        var replacement = jobs.control(job.id(), JobService.Action.RESTART, true);
        transfers.execute(replacement);
        completed(JobResponse.from(replacement));
        assertThat(jobs.get(job.id()).restartedAs()).isEqualTo(replacement.getId());
        assertThat(jobs.get(replacement.getId()).restartedFrom()).isEqualTo(job.id());
        assertThat(jobs.get(replacement.getId()).processedFiles()).isEqualTo(1);
        assertThat(Files.isSymbolicLink(third)).isTrue();
        assertThat(lineage.forFile(source.getId(), "first.txt", 0, 10).content()).hasSize(1);
        assertThat(lineage.forFile(source.getId(), "second.txt", 0, 10).content()).hasSize(1);
    }

    @Test
    void recallsDifferentFilesInParallel() throws IOException {
        s3.put("docs/first.txt", "first".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        s3.put("docs/second.txt", "second".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var gate = s3.blockRead("docs/", 2);
        var job = transfers.submit(RECALL, new TransferRequest(bucket.getId(), source.getId(),
                List.of("docs/", "docs/first.txt"), null, null, null, false));
        try {
            gate.awaitRead();
        } finally {
            gate.release.countDown();
        }
        completed(job);
        assertThat(jobs.get(job.id()).processedFiles()).isEqualTo(2);
        assertThat(root.resolve("docs/first.txt")).hasContent("first");
        assertThat(root.resolve("docs/second.txt")).hasContent("second");
    }

    @Test
    void logsTimingActorAndIndividualReasonsForPartialFailures() throws IOException {
        Files.writeString(root.resolve("copied.txt"), "verified bytes");
        Files.writeString(root.resolve("corrupt.txt"), "damaged during archive");
        s3.corruptReads = "corrupt.txt";
        var context = SecurityContextHolder.createEmptyContext();
        var token = Jwt.withTokenValue("test-token").header("alg", "none").subject("operator-id")
                .claim("preferred_username", "operator@example.com").claim("name", "Catalog Operator").build();
        context.setAuthentication(new JwtAuthenticationToken(token));
        SecurityContextHolder.setContext(context);
        try {
            var accepted = transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                    List.of("copied.txt", "corrupt.txt", "missing.txt"), null, null, ArchiveMode.COPY, false));
            var job = finished(accepted);
            assertThat(job.status()).isEqualTo(PARTIAL_FAILURE);
            assertThat(job.triggeredBy()).isEqualTo("operator@example.com");
            assertThat(job.triggeredByName()).isEqualTo("Catalog Operator");
            assertThat(job.startedAt()).isBetween(job.createdAt(), job.completedAt());
            assertThat(job.failedFiles()).isEqualTo(2);
            assertThat(jobs.fileResults(job.id(), false, PageRequest.of(0, 1)).totalElements()).isEqualTo(2);
            assertThat(jobs.fileResults(job.id(), true, PageRequest.of(0, 1)).content()).singleElement().satisfies(file -> {
                assertThat(file.sourcePath()).isEqualTo("corrupt.txt");
                assertThat(file.error()).contains("checksum mismatch");
            });
            assertThat(jobs.events(job.id(), true, PageRequest.of(0, 1)).content()).singleElement().satisfies(event -> {
                assertThat(event.path()).isEqualTo("missing.txt");
                assertThat(event.message()).isEqualTo("No regular files found");
            });
            assertThat(jobs.events(job.id(), false, PageRequest.of(0, 10)).content()).extracting(event -> event.message())
                    .contains("Job queued", "Execution started", "Execution completed with failures");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void retainsCopyAndSymlinkRecallEvidenceAndExportsFolderChecksums() throws Exception {
        Files.createDirectories(root.resolve("docs_%/nested"));
        Files.createDirectories(root.resolve("docs-other"));
        String path = "docs_%/nested/report.txt";
        Path report = Files.writeString(root.resolve(path), "audited bytes");
        Files.writeString(root.resolve("docs_%/pending.txt"), "never archived");
        Files.writeString(root.resolve("docs-other/other.txt"), "outside report scope");
        completed(catalog.submit(source.getId()));
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(report)));
        var copy = transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                List.of(path), null, null, ArchiveMode.COPY, false));
        completed(copy);
        var purge = transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                List.of(path), null, null, ArchiveMode.PURGE, false));
        completed(purge);
        assertThat(Files.isSymbolicLink(report)).isTrue();
        var recall = transfers.submit(RECALL, new TransferRequest(bucket.getId(), source.getId(),
                List.of(path), null, null, null, true));
        completed(recall);

        for (var connection : List.of(source, bucket)) {
            var movements = lineage.forFile(connection.getId(), path, 0, 10).content();
            assertThat(movements).extracting(item -> item.jobId()).containsExactly(recall.id(), purge.id(), copy.id());
            assertThat(movements).extracting(item -> item.operation()).containsExactly(
                    FileMovement.Operation.RECALL, FileMovement.Operation.ARCHIVE_PURGE, FileMovement.Operation.ARCHIVE_COPY);
            assertThat(movements).allSatisfy(item -> {
                assertThat(item.outcome()).isEqualTo(FileMovement.Outcome.VERIFIED);
                assertThat(item.sourceChecksum()).isEqualTo(expected);
                assertThat(item.targetChecksum()).isEqualTo(expected);
                assertThat(item.sourcePath()).isEqualTo(path);
                assertThat(item.targetPath()).isEqualTo(path);
            });
            assertThat(movements.getFirst().archiveRemoved()).isTrue();
            assertThat(movements.getFirst().symlinkReference()).startsWith("koonj-ref://");
            assertThat(movements.get(1).symlinkReference()).isEqualTo(movements.getFirst().symlinkReference());
            assertThat(movements.getLast().symlinkReference()).isNull();
        }
        assertThat(inventory(bucket)).isEmpty();

        var output = new ByteArrayOutputStream();
        lineage.folderReport(source.getId(), "docs_%/").writeTo(output);
        String csv = output.toString(StandardCharsets.UTF_8);
        assertThat(csv.lines().toList()).hasSize(5);
        assertThat(csv).contains("\"source_sha256\",\"target_sha256\"", "\"" + expected + "\",\"" + expected + "\"",
                "\"docs_%/pending.txt\",\"NO_RECORD\"", copy.id().toString(), recall.id().toString())
                .doesNotContain("docs-other");
    }

    @Test
    void failedArchiveAndRecallPreserveThePreviousPhysicalEntry() throws IOException {
        Path original = Files.writeString(root.resolve("report"), "original bytes");
        s3.corruptReads = "report";
        failed(transfers.submit(ARCHIVE, request(source, bucket, ArchiveMode.PURGE, false)));
        assertThat(original).hasContent("original bytes");
        assertThat(inventory(bucket)).isEmpty();
        assertThat(s3.objects).isEmpty();
        var evidence = lineage.forFile(source.getId(), "report", 0, 10).content().getFirst();
        assertThat(evidence.outcome()).isEqualTo(FileMovement.Outcome.FAILED);
        assertThat(evidence.sourceChecksum()).isNotBlank().isNotEqualTo(evidence.targetChecksum());
        assertThat(evidence.targetChecksum()).isNotBlank();

        s3.corruptReads = null;
        completed(transfers.submit(ARCHIVE, request(source, bucket, ArchiveMode.PURGE, false)));
        s3.corruptReads = "report";
        failed(transfers.submit(RECALL, request(bucket, source, null, true)));
        assertThat(Files.isSymbolicLink(original)).isTrue();
        assertThat(entry(source, "report").entryType()).isEqualTo(EntryType.SYMLINK);
        assertThat(entry(bucket, "report").entryType()).isEqualTo(EntryType.FILE);

        s3.corruptReads = null;
        s3.failDelete = "report";
        failed(transfers.submit(RECALL, request(bucket, source, null, true)));
        assertThat(Files.isSymbolicLink(original)).isTrue();
        assertThat(entry(source, "report").entryType()).isEqualTo(EntryType.SYMLINK);
        assertThat(entry(bucket, "report").entryType()).isEqualTo(EntryType.FILE);
    }

    private StorageConnection connection(ProviderType provider, String location) {
        var connection = new StorageConnection();
        connection.setAuthMode(provider == ProviderType.MINIO ? StorageAuthMode.SECRET_REF : StorageAuthMode.HOST_IDENTITY);
        connection.setName(UUID.randomUUID().toString());
        connection.setProviderType(provider);
        connection.setRole(StorageRole.BOTH);
        connection.setBasePathOrBucket(location);
        return connections.saveAndFlush(connection);
    }

    private JobResponse finished(JobResponse accepted) {
        await().pollInSameThread().atMost(Duration.ofSeconds(10)).until(() -> jobs.get(accepted.id()).completedAt() != null);
        return jobs.get(accepted.id());
    }

    private void completed(JobResponse accepted) {
        JobResponse job = finished(accepted);
        assertThat(job.status()).as(job.details()).isEqualTo(COMPLETED);
    }

    private void failed(JobResponse accepted) {
        assertThat(finished(accepted).status()).isEqualTo(FAILED);
    }

    private List<FileResponse> inventory(StorageConnection location) {
        return catalog.list(location.getId(), null, null, PageRequest.of(0, 50)).content();
    }

    private FileResponse entry(StorageConnection location, String path) {
        return inventory(location).stream().filter(file -> file.relativePath().equals(path)).findFirst().orElseThrow();
    }

    @Test
    void recallsOnlyTheArchivedLinksUnderTheSelectedMountedFolder() throws IOException {
        Files.createDirectories(root.resolve("selected_%/nested"));
        Files.createDirectory(root.resolve("selected-other"));
        Path inside = Files.writeString(root.resolve("selected_%/nested/report"), "archived in folder");
        Path outside = Files.writeString(root.resolve("selected-other/report"), "archived outside folder");
        Path localOnly = Files.writeString(root.resolve("selected_%/local"), "still local");
        completed(transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                List.of("selected_%/nested/report", "selected-other/report"), null, null, ArchiveMode.PURGE, false)));
        assertThat(Files.isSymbolicLink(inside)).isTrue();
        assertThat(Files.isSymbolicLink(outside)).isTrue();

        var recall = transfers.submit(RECALL, new TransferRequest(bucket.getId(), source.getId(),
                null, null, null, null, false, List.of("selected_%/")));
        assertThat(recall.requestedPaths()).containsExactly("selected_%/");
        var finished = finished(recall);
        assertThat(finished.status()).as(finished.details()).isEqualTo(COMPLETED);
        assertThat(finished.totalFiles()).isEqualTo(1);
        assertThat(inside).hasContent("archived in folder");
        assertThat(Files.isSymbolicLink(inside)).isFalse();
        assertThat(Files.isSymbolicLink(outside)).isTrue();
        assertThat(localOnly).hasContent("still local");
    }

    @Test
    void transfersOnlyEligibleFilesFromTheStorageRoot() throws IOException {
        Path link = Files.writeString(root.resolve("archived"), "archived bytes");
        Path regular = Files.writeString(root.resolve("local"), "local bytes");
        Path external = Files.createSymbolicLink(root.resolve("external"), regular);
        completed(transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                List.of("archived"), null, null, ArchiveMode.PURGE, false)));

        var archive = transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                List.of("/"), null, null, ArchiveMode.COPY, false));
        completed(archive);
        assertThat(jobs.get(archive.id()).processedFiles()).isEqualTo(1);
        assertThat(s3.objects).containsOnlyKeys("archived", "local");

        var recall = transfers.submit(RECALL, new TransferRequest(bucket.getId(), source.getId(),
                null, null, null, null, false, List.of("/")));
        completed(recall);
        assertThat(recall.requestedPaths()).containsExactly("/");
        assertThat(jobs.get(recall.id()).processedFiles()).isEqualTo(1);
        assertThat(link).hasContent("archived bytes");
        assertThat(regular).hasContent("local bytes");
        assertThat(Files.isSymbolicLink(external)).isTrue();
    }

    @Test
    void deduplicatesMountedLinksAndOverlappingS3Selections() throws IOException {
        Files.createDirectories(root.resolve("docs/nested"));
        Path first = Files.writeString(root.resolve("docs/first"), "first");
        Path second = Files.writeString(root.resolve("docs/nested/second"), "second");
        completed(transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                List.of("docs/"), null, null, ArchiveMode.PURGE, false)));

        var recall = transfers.submit(RECALL, new TransferRequest(bucket.getId(), source.getId(),
                List.of("docs/nested/", "docs/nested/second"), null, null, null, true, List.of("docs/", "docs/nested/")));
        completed(recall);

        assertThat(jobs.get(recall.id()).processedFiles()).isEqualTo(2);
        assertThat(jobs.fileResults(recall.id(), false, PageRequest.ofSize(10)).content()).hasSize(2);
        assertThat(first).hasContent("first");
        assertThat(second).hasContent("second");
        assertThat(s3.objects).isEmpty();
    }

    private TransferRequest request(StorageConnection from, StorageConnection to, ArchiveMode mode, boolean delete) {
        return new TransferRequest(from.getId(), to.getId(), List.of("report"), null, null, mode, delete);
    }

    static class ProjectTestTokens {
        final Map<String, Jwt> tokens = new ConcurrentHashMap<>();

        Jwt decode(String value) {
            Jwt token = tokens.get(value);
            if (token == null) throw new BadJwtException("Unknown test token");
            return token;
        }
    }

    @TestConfiguration
    static class ConnectorConfiguration {
        @Bean ProjectTestTokens projectTestTokens() { return new ProjectTestTokens(); }

        @Bean @Primary
        JwtDecoder projectTestDecoder(ProjectTestTokens tokens) { return tokens::decode; }

        @Bean BucketFixture bucketFixture() { return new BucketFixture(new TestBucket()); }

        @Bean @Primary
        StorageConnectorFactory testConnectorFactory(BucketFixture fixture) {
            return new StorageConnectorFactory(List.of(new LocalStorageConnector(), new SmbStorageConnector(), fixture.connector()));
        }
    }

    private record BucketFixture(TestBucket connector) {}

    static class TestBucket implements StorageConnector {
        private record ObjectData(byte[] bytes, Instant modified) {}
        private final Map<String, ObjectData> objects = new ConcurrentHashMap<>();
        private volatile ReadGate gate;
        private volatile String corruptReads;
        private volatile String failDelete;
        private volatile Instant coldReady;

        void reset() {
            objects.clear();
            gate = null;
            corruptReads = null;
            failDelete = null;
            coldReady = null;
        }

        ReadGate blockRead(String pathPrefix) {
            return blockRead(pathPrefix, 1);
        }

        ReadGate blockRead(String pathPrefix, int readers) {
            return blockRead(pathPrefix, readers, Duration.ofSeconds(10));
        }

        ReadGate blockRead(String pathPrefix, int readers, Duration timeout) {
            gate = new ReadGate(pathPrefix, new CountDownLatch(readers), new CountDownLatch(1), timeout);
            return gate;
        }

        void coldReady(Instant ready) { coldReady = ready; }

        void put(String path, byte[] bytes) { objects.put(path, new ObjectData(bytes, Instant.now())); }

        @Override public boolean supports(ProviderType type) {
            return type != ProviderType.LOCAL && type != ProviderType.SMB && type != ProviderType.NAS;
        }
        @Override public boolean objectStorage() { return true; }
        @Override public Optional<Instant> requestColdRestore(StorageConnection connection, String key) {
            return Optional.ofNullable(coldReady);
        }
        @Override public void checkConnectivity(StorageConnection config) {}
        @Override public void testConnection(StorageConnection config) {}
        @Override
        public void testConnection(StorageConnection config, software.amazon.awssdk.auth.credentials.AwsCredentials credentials)
                throws IOException {
            if ("invalid-fixture".equals(credentials.accessKeyId())) throw new IOException("The storage probe rejected the credential");
        }

        @Override
        public List<ScannedFileInfo> listFiles(StorageConnection config, String path) {
            return objects.entrySet().stream()
                    .filter(item -> !StoragePaths.isArchiveStagingKey(item.getKey()))
                    .filter(item -> path.isEmpty() || item.getKey().equals(path) || item.getKey().startsWith(path + "/"))
                    .map(item -> new ScannedFileInfo(item.getKey(), item.getValue().bytes.length,
                            item.getValue().modified, "STANDARD")).toList();
        }

        @Override
        public InputStream openReadStream(StorageConnection config, String path) throws IOException {
            ReadGate current = gate;
            if (current != null && path.startsWith(current.pathPrefix)) {
                current.entered.countDown();
                try {
                    if (!current.release.await(current.timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IOException("Read gate timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            byte[] bytes = objects.get(path).bytes.clone();
            if (path.equals(corruptReads)) bytes[0] ^= 1;
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void writeStream(StorageConnection config, String path, InputStream data, long size, String storageClass)
                throws IOException {
            byte[] bytes = data.readAllBytes();
            if (bytes.length != size) throw new IOException("Unexpected length");
            put(path, bytes);
        }

        @Override public void deleteFile(StorageConnection config, String path) throws IOException {
            if (path.equals(failDelete)) throw new IOException("Delete failed");
            objects.remove(path);
        }
    }

    record ReadGate(String pathPrefix, CountDownLatch entered, CountDownLatch release, Duration timeout) {
        void awaitRead() {
            await().atMost(timeout).until(() -> entered.getCount() == 0);
        }
    }
}
