package uk.appsthatmatter.koonj.server.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OtlpApplicationLogs {
    private static final String APPLICATION_PACKAGE = "uk.appsthatmatter.koonj.";
    private final String endpoint;
    private final String authorization;
    private final String serviceName;
    private final String instanceId;
    private Logger rootLogger;
    private SdkLoggerProvider provider;
    private UnsynchronizedAppenderBase<ILoggingEvent> appender;

    public OtlpApplicationLogs(@Value("${koonj.observability.logs-endpoint:}") String endpoint,
                               @Value("${OPENOBSERVE_AUTHORIZATION:}") String authorization,
                               @Value("${spring.application.name}") String serviceName,
                               @Value("${HOSTNAME:${COMPUTERNAME:}}") String instanceId) {
        this.endpoint = endpoint;
        this.authorization = authorization;
        this.serviceName = serviceName;
        this.instanceId = instanceId;
    }

    @PostConstruct
    void start() {
        if (endpoint.isBlank()) return;
        var exporter = OtlpHttpLogRecordExporter.builder().setEndpoint(endpoint).setTimeout(Duration.ofSeconds(5))
                .addHeader("stream-name", "koonj_server");
        if (!authorization.isBlank()) exporter.addHeader("Authorization", authorization);
        var attributes = Attributes.builder().put("service.name", serviceName);
        if (!instanceId.isBlank()) attributes.put("service.instance.id", instanceId);
        provider = SdkLoggerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(attributes.build())))
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(exporter.build()).build())
                .build();
        var logger = provider.get(serviceName);
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new UnsynchronizedAppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                String name = event.getLoggerName();
                Level level = event.getLevel();
                // Export framework failures without feeding SDK export diagnostics back into the batch.
                if (name.startsWith("io.opentelemetry")
                        || (!name.startsWith(APPLICATION_PACKAGE) && !level.isGreaterOrEqual(Level.ERROR))) return;
                var record = logger.logRecordBuilder().setTimestamp(event.getInstant()).setContext(Context.current())
                        .setSeverity(switch (level.toInt()) {
                            case Level.ERROR_INT -> Severity.ERROR;
                            case Level.WARN_INT -> Severity.WARN;
                            case Level.DEBUG_INT -> Severity.DEBUG;
                            case Level.TRACE_INT -> Severity.TRACE;
                            default -> Severity.INFO;
                        }).setSeverityText(level == Level.TRACE ? "DETAIL" : level.toString())
                        .setBody(event.getFormattedMessage())
                        .setAttribute("logger.name", name);
                var fields = event.getKeyValuePairs();
                if (fields != null) {
                    fields.forEach(field -> record.setAttribute(field.key, String.valueOf(field.value)));
                }
                var failure = event.getThrowableProxy();
                if (failure != null) {
                    record.setAttribute("exception.type", failure.getClassName());
                    record.setAttribute("exception.stacktrace", ThrowableProxyUtil.asString(failure));
                }
                record.emit();
            }
        };
        appender.setContext(rootLogger.getLoggerContext());
        appender.setName("KOONJ_OTLP_LOGS");
        appender.start();
        rootLogger.addAppender(appender);
    }

    @PreDestroy
    void stop() {
        if (appender == null) return;
        rootLogger.detachAppender(appender);
        appender.stop();
        provider.close();
    }
}
