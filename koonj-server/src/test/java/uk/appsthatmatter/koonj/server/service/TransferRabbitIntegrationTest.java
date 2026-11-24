package uk.appsthatmatter.koonj.server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.server.KoonjServerApplication;
import uk.appsthatmatter.koonj.server.connector.LocalStorageConnector;
import uk.appsthatmatter.koonj.server.connector.SmbStorageConnector;
import uk.appsthatmatter.koonj.server.connector.StorageConnectorFactory;
import uk.appsthatmatter.koonj.server.connector.StoragePaths;
import uk.appsthatmatter.koonj.server.dto.TransferRequest;
import uk.appsthatmatter.koonj.server.entity.ArchiveMode;
import uk.appsthatmatter.koonj.server.entity.Project;
import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;
import uk.appsthatmatter.koonj.server.entity.StorageRole;
import uk.appsthatmatter.koonj.server.repository.FileMetadataRepository;
import uk.appsthatmatter.koonj.server.repository.ProjectRepository;
import uk.appsthatmatter.koonj.server.repository.StorageConnectionRepository;
import uk.appsthatmatter.koonj.server.repository.TransferJobRepository;
import uk.appsthatmatter.koonj.server.repository.TransferReservationRepository;
import uk.appsthatmatter.koonj.server.security.ProjectScope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static uk.appsthatmatter.koonj.server.entity.TransferJob.Operation.ARCHIVE;
import static uk.appsthatmatter.koonj.server.entity.TransferJob.Operation.RECALL;
import static uk.appsthatmatter.koonj.server.entity.TransferJob.Status.*;

@EnabledIfEnvironmentVariable(named = "KOONJ_TRANSFER_IT_POSTGRES_URL", matches = ".+")
class TransferRabbitIntegrationTest {
    private static final PhysicalCatalogIntegrationTest.TestBucket OBJECTS = new PhysicalCatalogIntegrationTest.TestBucket();

    @TempDir Path root;

    @Test
    void dispatchesAcrossReplicasWhileRejectingOverlapAndDuplicateDeliveries() throws Exception {
        var selected = Files.createDirectory(root.resolve("selected"));
        Files.writeString(selected.resolve("report"), "selected report");
        Files.writeString(root.resolve("separate"), "separate report");
        try (var api = application(false)) {
            var local = connection(api, ProviderType.LOCAL, root.toString());
            var bucket = connection(api, ProviderType.MINIO, "transfer-" + UUID.randomUUID());
            var alias = connection(api, ProviderType.MINIO, bucket.getBasePathOrBucket());
            var submissions = api.getBean(FileTransferService.class);
            var jobs = api.getBean(TransferJobRepository.class);
            var first = submissions.submit(ARCHIVE, request(local, bucket, "selected/", ArchiveMode.PURGE));
            assertThat(jobs.findById(first.id()).orElseThrow().getStatus()).isEqualTo(PENDING);
            assertThatThrownBy(() -> submissions.submit(RECALL, request(alias, local, "selected/report", ArchiveMode.COPY)))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");

            var gate = OBJECTS.blockRead(StoragePaths.ARCHIVE_STAGING_PREFIX, 2, Duration.ofSeconds(30));
            try (var worker = application(true)) {
                await().atMost(Duration.ofSeconds(10))
                        .until(() -> jobs.findById(first.id()).orElseThrow().getStatus() == RUNNING);
                var running = jobs.findById(first.id()).orElseThrow();
                try (var replica = application(false)) {
                    assertThat(jobs.findById(first.id()).orElseThrow().getWorkerAttempt()).isEqualTo(running.getWorkerAttempt());
                    worker.getBean(RabbitTemplate.class).convertAndSend(TransferRabbit.EXCHANGE,
                            TransferRabbit.route("default", false), first.id() + ":" + running.getDispatchGeneration());

                    UUID otherProject = UUID.randomUUID();
                    replica.getBean(ProjectRepository.class).saveAndFlush(new Project(otherProject, "Other project"));
                    var otherLocal = connection(replica, ProviderType.LOCAL, root.toString(), otherProject);
                    var otherBucket = connection(replica, ProviderType.MINIO, bucket.getBasePathOrBucket(), otherProject);
                    var secondId = new AtomicReference<UUID>();
                    var otherTransfers = replica.getBean(FileTransferService.class);
                    try {
                        ProjectScope.run(otherProject, () -> {
                            assertThatThrownBy(() -> otherTransfers.submit(RECALL,
                                    request(otherBucket, otherLocal, "selected/report", ArchiveMode.COPY)))
                                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
                            secondId.set(otherTransfers.submit(ARCHIVE,
                                    request(otherLocal, otherBucket, "separate", ArchiveMode.COPY)).id());
                        });
                        gate.awaitRead();
                        var active = jobs.findById(first.id()).orElseThrow();
                        assertThat(active.getStatus()).as(active.getDetails()).isEqualTo(RUNNING);
                        assertThat(jobs.findById(secondId.get()).orElseThrow().getStatus()).isEqualTo(RUNNING);
                        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                                assertThat(api.getBean(JobService.class).activeArchives(local.getId()))
                                        .anySatisfy(activity -> assertThat(activity.currentFiles()).contains("selected/report")));
                        assertThat(jobs.findById(first.id()).orElseThrow().getWorkerAttempt()).isEqualTo(running.getWorkerAttempt());
                    } finally {
                        gate.release().countDown();
                    }
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                        assertThat(jobs.findById(first.id()).orElseThrow().getStatus()).isEqualTo(COMPLETED);
                        assertThat(jobs.findById(secondId.get()).orElseThrow().getStatus()).isEqualTo(COMPLETED);
                    });
                }
                assertThat(api.getBean(TransferReservationRepository.class).findByJobId(first.id())).isEmpty();
                var file = api.getBean(FileMetadataRepository.class)
                        .findByConnectionIdAndRelativePath(local.getId(), "selected/report").orElseThrow();
                OBJECTS.coldReady(Instant.now().plus(Duration.ofHours(5)));
                try {
                    assertThat(submissions.recallSync(file.getId(), 10).status()).isEqualTo("RESTORING_GLACIER");
                    assertThatThrownBy(() -> submissions.submit(RECALL, request(bucket, local, "selected/report", ArchiveMode.COPY)))
                            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
                    assertThat(worker.getBean(FileTransferService.class).recallSync(file.getId(), 5).status())
                            .isEqualTo("RESTORING_GLACIER");
                } finally {
                    OBJECTS.coldReady(null);
                }
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(Files.isSymbolicLink(selected.resolve("report"))).isFalse();
                    assertThat(selected.resolve("report")).hasContent("selected report");
                });
            } finally {
                gate.release().countDown();
            }
        }
    }

    private static ConfigurableApplicationContext application(boolean worker) {
        return new SpringApplicationBuilder(KoonjServerApplication.class, Connectors.class).run(
                "--server.port=0", "--spring.jpa.hibernate.ddl-auto=update",
                "--spring.datasource.url=" + System.getenv("KOONJ_TRANSFER_IT_POSTGRES_URL"),
                "--spring.datasource.username=" + System.getenv().getOrDefault("KOONJ_TRANSFER_IT_USERNAME", "transfer_test"),
                "--spring.datasource.password=" + System.getenv().getOrDefault("KOONJ_TRANSFER_IT_PASSWORD", "transfer_test"),
                "--spring.rabbitmq.host=127.0.0.1",
                "--spring.rabbitmq.port=" + System.getenv().getOrDefault("KOONJ_TRANSFER_IT_RABBIT_PORT", "55637"),
                "--spring.rabbitmq.username=" + System.getenv().getOrDefault("KOONJ_TRANSFER_IT_RABBIT_USERNAME", "transfer_test"),
                "--spring.rabbitmq.password=" + System.getenv().getOrDefault("KOONJ_TRANSFER_IT_RABBIT_PASSWORD", "transfer_test"),
                "--spring.rabbitmq.virtual-host=" + System.getenv().getOrDefault("KOONJ_TRANSFER_IT_RABBIT_VHOST", "transfer_test"),
                "--koonj.transfer.rabbit.enabled=true", "--koonj.transfer.rabbit.worker-enabled=" + worker,
                "--koonj.transfer.rabbit.relay-enabled=" + worker, "--koonj.transfer.rabbit.cold-poll=1s");
    }

    private static StorageConnection connection(ConfigurableApplicationContext context, ProviderType provider, String path) {
        return connection(context, provider, path, ProjectScope.LEGACY);
    }

    private static StorageConnection connection(ConfigurableApplicationContext context, ProviderType provider, String path, UUID project) {
        var connection = new StorageConnection();
        connection.setProjectId(project);
        connection.setName(UUID.randomUUID().toString());
        connection.setProviderType(provider);
        connection.setRole(StorageRole.BOTH);
        connection.setBasePathOrBucket(path);
        return context.getBean(StorageConnectionRepository.class).saveAndFlush(connection);
    }

    private static TransferRequest request(StorageConnection source, StorageConnection target, String path, ArchiveMode mode) {
        return new TransferRequest(source.getId(), target.getId(), List.of(path), null, null, mode, false);
    }

    @TestConfiguration
    static class Connectors {
        @Bean @Primary
        StorageConnectorFactory integrationConnectors() {
            return new StorageConnectorFactory(List.of(new LocalStorageConnector(), new SmbStorageConnector(), OBJECTS));
        }
    }
}
