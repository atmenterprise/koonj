package uk.appsthatmatter.koonj.server.storageauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.appsthatmatter.koonj.server.dto.StorageCredentialInput;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.appsthatmatter.koonj.server.connector.S3CompatibleStorageConnector;
import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageAuthMode;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;
import uk.appsthatmatter.koonj.server.security.ProjectScope;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageAuthTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final String ENDPOINT = "https://archive.example.test";
    @Test
    void envelopesBindToProjectConnectionAndTargetAndSurviveKeyRotation() throws Exception {
        var kms = new AwsKmsWrappingKeys("", "");
        String first = "first:" + fixtureKey((byte) 1);
        String second = "next:" + fixtureKey((byte) 2);
        var old = new StorageCredentialEncryption(JSON, kms, "local", "first", first);
        var current = new StorageCredentialEncryption(JSON, kms, "local", "next", first + "," + second);
        var target = new CredentialTarget(PROJECT, UUID.randomUUID(), ProviderType.MINIO, "archive",
                ENDPOINT, "us-east-1", "archive-path", true);
        var input = new StorageCredentialInput("fixture-access-key", "fixture-credential-secret");
        byte[] firstEnvelope = old.encrypt(target, input);
        assertThat(new String(firstEnvelope, StandardCharsets.UTF_8)).doesNotContain(input.accessKeyId(), input.secretAccessKey());
        assertThat(current.decrypt(target, firstEnvelope).credentials().accessKeyId()).isEqualTo(input.accessKeyId());
        assertThat(current.encrypt(target, input)).isNotEqualTo(current.encrypt(target, input));
        assertThat(JSON.writeValueAsString(input)).doesNotContain(input.accessKeyId(), input.secretAccessKey());
        for (var wrongTarget : java.util.List.of(
                new CredentialTarget(UUID.randomUUID(), target.connection(), target.provider(), target.bucket(),
                        target.endpoint(), target.region(), target.prefix(), target.pathStyle()),
                new CredentialTarget(target.project(), UUID.randomUUID(), target.provider(), target.bucket(),
                        target.endpoint(), target.region(), target.prefix(), target.pathStyle()),
                new CredentialTarget(target.project(), target.connection(), target.provider(), target.bucket(),
                        "https://another.invalid", target.region(), target.prefix(), target.pathStyle()))) {
            assertThatThrownBy(() -> current.decrypt(wrongTarget, firstEnvelope))
                    .isInstanceOf(StorageCredentialException.class).hasNoCause();
        }
        var tampered = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(firstEnvelope);
        tampered.put("revision", UUID.randomUUID().toString());
        byte[] modified = JSON.writeValueAsBytes(tampered);
        assertThatThrownBy(() -> current.decrypt(target, modified)).isInstanceOf(StorageCredentialException.class);
        var retired = new StorageCredentialEncryption(JSON, kms, "local", "next", second);
        assertThatThrownBy(() -> retired.decrypt(target, firstEnvelope)).hasMessageContaining("unavailable");
        assertThat(retired.decrypt(target, current.encrypt(target, input)).credentials().accessKeyId()).isEqualTo(input.accessKeyId());
    }

    private static String fixtureKey(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void credentialRefreshIsProjectBoundAndFailsClosedAtTheRefreshDeadline() {
        var time = new MutableClock();
        var reads = new AtomicInteger();
        SecretProvider backend = new SecretProvider() {
            @Override
            public VersionedSecret read(String path) {
                assertThat(path).isEqualTo("koonj/" + PROJECT + "/archive");
                int version = reads.incrementAndGet();
                if (version == 3) throw new StorageCredentialException("Store unavailable");
                var fields = JSON.createObjectNode().put("bucket", "archive").put("endpointUrl", ENDPOINT)
                        .put("objectPrefix", "safe/path").put("accessKeyId", "fixture-" + version)
                        .put("secretAccessKey", "fixture-secret").put("sessionToken", "fixture-token")
                        .put("expiresAt", time.instant().plusSeconds(8).toString());
                return new VersionedSecret(Integer.toString(version), fields);
            }
        };
        var reference = SecretReference.parse("aws-sm:archive");
        var provider = new RefreshingSecretCredentials("aws-sm",
                () -> backend.read(reference.path(PROJECT)).forTarget("archive", ENDPOINT, "safe/path"),
                PROJECT, UUID.randomUUID(), Duration.ofSeconds(30), time);

        assertThat(provider.resolveCredentials()).isInstanceOf(AwsSessionCredentials.class);
        assertThat(provider.resolveCredentials().accessKeyId()).isEqualTo("fixture-1");
        time.advance(7);
        assertThat(provider.resolveCredentials().accessKeyId()).isEqualTo("fixture-2");
        time.advance(7);
        assertThatThrownBy(provider::resolveCredentials).hasMessage("Store unavailable");
        assertThatThrownBy(provider::resolveCredentials).hasMessage("Store unavailable");
        assertThat(reads.get()).isEqualTo(3);
        time.advance(2);
        assertThat(provider.resolveCredentials().accessKeyId()).isEqualTo("fixture-4");
        assertThatThrownBy(() -> backend.read("koonj/" + PROJECT + "/archive")
                .forTarget("archive", "https://wrong.example.test", "safe/path")).hasMessageContaining("not bound");
        assertThatThrownBy(() -> SecretReference.parse("../different-project/archive"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multipartS3RequestsPickUpRotationAndServiceErrorsAreSanitized() throws Exception {
        var refreshedParts = new AtomicInteger();
        var denyRequests = new AtomicBoolean(false);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        var connection = new StorageConnection();
        connection.setId(UUID.randomUUID());
        connection.setProviderType(ProviderType.CUSTOM_S3);
        connection.setAuthMode(StorageAuthMode.ENCRYPTED_CREDENTIALS);
        connection.setBasePathOrBucket("archive");
        connection.setEndpointUrl(endpoint);
        connection.setObjectPrefix("project-root");

        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                create table storage_connections(id uuid primary key, project_id uuid, auth_mode varchar(40),
                credential_envelope varbinary(16384))
                """);
        var encryption = new StorageCredentialEncryption(JSON, new AwsKmsWrappingKeys("", ""),
                "local", "test", "test:" + fixtureKey((byte) 11));
        var target = CredentialTarget.from(connection);
        byte[] first = encryption.encrypt(target, new StorageCredentialInput("fixture-1", "fake-test-key"));
        jdbc.update("insert into storage_connections values (?, ?, 'ENCRYPTED_CREDENTIALS', ?)",
                connection.getId(), connection.getProjectId(), first);
        byte[] replacement = encryption.encrypt(target, new StorageCredentialInput("fixture-2", "replacement-test-key"));
        server.createContext("/archive", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String verb = exchange.getRequestMethod();
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            if (denyRequests.get()) {
                respond(exchange, 403, "<Error><Code>AccessDenied</Code><Message>sensitive-fixture</Message></Error>");
                return;
            }
            if ("GET".equals(verb)) {
                if (query == null || !query.contains("prefix=project-root")) {
                    respond(exchange, 400, "<Error><Code>InvalidRequest</Code></Error>");
                    return;
                }
                respond(exchange, 200, """
                        <ListBucketResult><IsTruncated>false</IsTruncated><Contents>
                        <Key>project-root/existing.txt</Key><Size>5</Size>
                        <LastModified>2026-01-01T00:00:00Z</LastModified><StorageClass>STANDARD</StorageClass>
                        </Contents></ListBucketResult>
                        """);
                return;
            }
            if (!"/archive/project-root/large-file".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 400, "<Error><Code>InvalidRequest</Code></Error>");
                return;
            }
            if ("POST".equals(verb) && query != null && query.contains("uploads")) {
                jdbc.update("update storage_connections set credential_envelope = ? where id = ?",
                        replacement, connection.getId());
                try {
                    Thread.sleep(1150);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
                respond(exchange, 200, """
                        <InitiateMultipartUploadResult><Bucket>archive</Bucket><Key>project-root/large-file</Key>
                        <UploadId>test-upload</UploadId></InitiateMultipartUploadResult>
                        """);
            } else if ("PUT".equals(verb) && query != null && query.contains("partNumber")) {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (authorization != null && authorization.contains("Credential=fixture-2/")) refreshedParts.incrementAndGet();
                exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
                exchange.getResponseHeaders().set("ETag", "\"part-etag\"");
                respond(exchange, 200, "");
            } else if ("POST".equals(verb) && query != null && query.contains("uploadId")) {
                exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
                respond(exchange, 200, """
                        <CompleteMultipartUploadResult><Bucket>archive</Bucket><Key>project-root/large-file</Key>
                        <ETag>"completed"</ETag></CompleteMultipartUploadResult>
                        """);
            } else {
                respond(exchange, 400, "<Error><Code>InvalidRequest</Code></Error>");
            }
        });
        server.start();
        var factory = new S3CredentialFactory(path -> { throw new AssertionError("Unexpected external credential read"); },
                new DatabaseStorageCredentials(jdbc, encryption), JSON,
                new StorageAuthentication("", false, true), Duration.ofSeconds(1));
        try (var connector = new S3CompatibleStorageConnector(factory)) {
            assertThat(connector.listFiles(connection, "")).extracting("relativePath").containsExactly("existing.txt");
            byte[] body = new byte[32 * 1024 * 1024];
            connector.writeStream(connection, "large-file", new ByteArrayInputStream(body), body.length, "STANDARD");
            assertThat(refreshedParts.get()).isPositive();

            denyRequests.set(true);
            assertThatThrownBy(() -> connector.checkConnectivity(connection)).isInstanceOf(S3Exception.class)
                    .hasMessageContaining("403").hasMessageNotContaining("sensitive-fixture").hasNoCause();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void awsRoleIdentityAndSessionPolicyAreScoped() throws IOException {
        var authentication = new StorageAuthentication("test-namespace", false, false);
        String role = "arn:aws:iam::123456789012:role/archive";
        String externalId = authentication.externalId(PROJECT, role);
        assertThat(authentication.externalId(PROJECT, role + "-secondary")).isEqualTo(externalId);
        assertThat(authentication.externalId(UUID.randomUUID(), role)).isNotEqualTo(externalId);
        assertThat(authentication.externalId(PROJECT, role.replace("123456789012", "123456789013"))).isNotEqualTo(externalId);
        var factory = new S3CredentialFactory(null, null, JSON, authentication, Duration.ofSeconds(30));
        var policy = JSON.readTree(factory.sessionPolicy("aws", "archive", "data/project"));
        assertThat(policy.at("/Statement/0/Condition/StringLike/s3:prefix").asText()).isEqualTo("data/project/*");
        assertThat(policy.at("/Statement/1/Resource").asText()).isEqualTo("arn:aws:s3:::archive/data/project/*");
        assertThatThrownBy(() -> factory.sessionPolicy("aws", "archive", "data/*")).hasMessageContaining("unsupported");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
