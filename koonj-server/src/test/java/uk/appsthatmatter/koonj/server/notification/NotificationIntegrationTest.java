package uk.appsthatmatter.koonj.server.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import uk.appsthatmatter.koonj.notification.NotificationDeliveryRepository;
import uk.appsthatmatter.koonj.notification.NotificationEvent;
import uk.appsthatmatter.koonj.notification.NotificationOutboxRepository;
import uk.appsthatmatter.koonj.server.entity.TransferJob;
import uk.appsthatmatter.koonj.server.service.JobService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static uk.appsthatmatter.koonj.notification.NotificationEvent.Type.LARGE_BATCH_COMPLETED;

@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notifications;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "koonj.security.basic-auth.enabled=true", "koonj.security.basic-auth.username=legacy",
        "koonj.security.basic-auth.password=test-password", "koonj.security.basic-auth.role=KOONJ_ADMIN"
})
class NotificationIntegrationTest {
    private static final String BASE = "/api/v1/notifications";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JobService jobs;
    @Autowired NotificationOutboxWriter writer;
    @Autowired NotificationOutboxRepository outbox;
    @Autowired NotificationDeliveryRepository deliveries;
    @Autowired TransactionTemplate transactions;

    @Test
    void basicAuthWritesRequireCsrfToken() throws Exception {
        String basic = "Basic " + HttpHeaders.encodeBasicAuth("legacy", "test-password", null);
        var write = post(BASE + "/channels").header("Authorization", basic)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                        "name", "Legacy SMTP", "type", "EMAIL", "enabled", true, "smtpPassword", "write-only",
                        "adminRecipients", List.of("admin@example.test"))));
        mvc.perform(write).andExpect(status().isForbidden());

        var result = mvc.perform(get("/api/v1/csrf").header("Authorization", basic))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/api/v1/csrf").session(session)).andExpect(status().isUnauthorized());
        var csrf = json.readTree(result.getResponse().getContentAsString());
        mvc.perform(write.session(session)
                        .header(csrf.get("headerName").asText(), csrf.get("token").asText()))
                .andExpect(status().isCreated());
    }

    @Test
    void testChannelAndJobWriteToOutboxWhileOnlyTheWorkerCreatesDeliveries() throws Exception {
        var created = mvc.perform(post(BASE + "/channels").header("Authorization", "Bearer admin")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                        "name", "SMTP", "type", "EMAIL", "enabled", true, "smtpPassword", "write-only",
                        "adminRecipients", List.of("admin@example.test")))))
                .andExpect(status().isCreated()).andReturn();
        var channel = json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get(BASE + "/channels/" + channel).header("Authorization", "Bearer owner"))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/channels/" + channel).header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasSmtpPassword").value(true))
                .andExpect(jsonPath("$.smtpPassword").doesNotHaveJsonPath());
        mvc.perform(get(BASE + "/preferences/me")).andExpect(status().isUnauthorized());
        mvc.perform(put(BASE + "/preferences/me").header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"emailNotificationsEnabled":true,"mutedEventTypes":["LARGE_BATCH_COMPLETED"],"silencedUntil":null}
                        """)).andExpect(status().isOk()).andExpect(jsonPath("$.mutedEventTypes[0]").value("LARGE_BATCH_COMPLETED"));
        mvc.perform(post(BASE + "/channels/" + channel + "/test").header("Authorization", "Bearer admin"))
                .andExpect(status().isAccepted());
        assertThat(outbox.findAll()).anyMatch(row -> UUID.fromString(channel).equals(row.getTestChannelId()));

        var job = queuedBatch();
        jobs.execute(job, running -> {
            jobs.totals(running, 1, 16);
            jobs.progress(running, 16, null);
        }).get(5, TimeUnit.SECONDS);
        assertThat(outbox.findAll()).anyMatch(row -> row.getTestChannelId() == null
                && row.getEvent().key().equals(job.getId().toString())
                && row.getEvent().actorEmail().equals("owner@example.test"));
        assertThat(deliveries.count()).isZero();
    }

    @Test
    void outboxWriteRollsBackWithItsTransaction() {
        var event = new NotificationEvent(LARGE_BATCH_COMPLETED, UUID.randomUUID().toString(), null, null, "Test", Map.of());
        transactions.executeWithoutResult(tx -> {
            writer.enqueue(event);
            tx.setRollbackOnly();
        });
        assertThat(outbox.findAll()).noneMatch(row -> row.getEvent().key().equals(event.key()));
    }

    private TransferJob queuedBatch() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(token("owner")));
        SecurityContextHolder.setContext(context);
        try {
            return jobs.create(TransferJob.Operation.ARCHIVE, UUID.randomUUID(), UUID.randomUUID(), List.of("asset"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static Jwt token(String value) {
        if (!List.of("admin", "owner").contains(value)) throw new BadJwtException("Unknown test user");
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(value)
                .claim("realm_access", Map.of("roles", List.of(value.equals("admin") ? "KOONJ_ADMIN" : "KOONJ_OPERATOR")))
                .claim("email", value + "@example.test").claim("email_verified", true).build();
    }

    @TestConfiguration
    static class Fixtures {
        @Bean @Primary
        JwtDecoder testTokens() { return NotificationIntegrationTest::token; }
    }
}
