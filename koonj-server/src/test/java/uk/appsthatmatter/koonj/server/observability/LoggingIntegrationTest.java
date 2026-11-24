package uk.appsthatmatter.koonj.server.observability;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import uk.appsthatmatter.koonj.server.KoonjServerApplication;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LoggingIntegrationTest {
    private static final Logger LOG = LoggerFactory.getLogger(LoggingIntegrationTest.class);

    @Test
    void writesConsoleAndFileAndExportsOtlp(CapturedOutput console, @TempDir Path logs) throws Exception {
        Path logFile = logs.resolve("server.log");
        BlockingQueue<Export> exports = new LinkedBlockingQueue<>();
        var receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/api/default/v1/logs", request -> {
            try (request) {
                var headers = request.getRequestHeaders();
                exports.add(new Export(headers.getFirst("Authorization"), headers.getFirst("stream-name"),
                        new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                request.sendResponseHeaders(200, -1);
            }
        });
        receiver.start();
        // Other application contexts in this test JVM may have initialized console-only logging.
        LoggingSystem.get(KoonjServerApplication.class.getClassLoader()).cleanUp();
        try (var context = new SpringApplicationBuilder(KoonjServerApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:h2:mem:logging-test;MODE=PostgreSQL",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=create-drop",
                "--KOONJ_LOG_LEVEL=TRACE", "--KOONJ_LOG_FILE=" + logFile, "--OPENOBSERVE_AUTHORIZATION=Basic test-value",
                "--KOONJ_OTLP_LOGS_ENDPOINT=http://127.0.0.1:" + receiver.getAddress().getPort() + "/api/default/v1/logs")) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            try (var client = HttpClient.newHttpClient()) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/actuator/health?privateValue=do-not-log")).timeout(Duration.ofSeconds(10)).build();
                assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            }
            LOG.info("Logging test information");
            LOG.debug("Logging test diagnostic");
            LOG.atError().addKeyValue("job.id", "logging-test-job")
                    .setCause(new IllegalStateException("Logging test cause")).log("Logging test failure");
            assertExport(exports);
        } finally {
            receiver.stop(0);
        }
        String[] expected = {"DETAIL", "Logging test information", "Logging test diagnostic", "Logging test failure"};
        assertThat(console.getOut()).contains(expected).doesNotContain("do-not-log");
        assertThat(Files.readString(logFile)).contains(expected).doesNotContain("do-not-log");
    }

    private static void assertExport(BlockingQueue<Export> exports) throws InterruptedException {
        var expected = new String[] {"HTTP GET /actuator/health completed with status 200",
                "Logging test information", "Logging test diagnostic", "Logging test failure"};
        var payload = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!payload.toString().contains("Logging test failure")) {
            Export export = exports.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertThat(export).as("OTLP export containing the test failure").isNotNull();
            assertThat(export.authorization()).isEqualTo("Basic test-value");
            assertThat(export.stream()).isEqualTo("koonj_server");
            payload.append(export.body());
        }
        assertThat(payload).contains(expected).contains("DETAIL", "INFO", "DEBUG", "ERROR", "job.id", "logging-test-job",
                "exception.type", "exception.stacktrace", "IllegalStateException: Logging test cause")
                .doesNotContain("do-not-log");
    }

    private record Export(String authorization, String stream, String body) {}
}
