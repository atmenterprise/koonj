package uk.appsthatmatter.koonj.server.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
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
    private final String endpoint;
    private final String authorization;
    private final String serviceName;
    private Logger applicationLogger;
    private SdkLoggerProvider provider;
    private UnsynchronizedAppenderBase<ILoggingEvent> appender;

    public OtlpApplicationLogs(@Value("${koonj.observability.logs-endpoint:}") String endpoint,
                               @Value("${OPENOBSERVE_AUTHORIZATION:}") String authorization,
                               @Value("${spring.application.name}") String serviceName) {
        this.endpoint = endpoint;
        this.authorization = authorization;
        this.serviceName = serviceName;
    }

    @PostConstruct
    void start() {
        if (endpoint.isBlank()) return;
        var exporter = OtlpHttpLogRecordExporter.builder().setEndpoint(endpoint).setTimeout(Duration.ofSeconds(5))
                .addHeader("stream-name", "koonj_server");
        if (!authorization.isBlank()) exporter.addHeader("Authorization", authorization);
        provider = SdkLoggerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.builder().put("service.name", serviceName).build())))
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(exporter.build()).build())
                .build();
        var logger = provider.get(serviceName);
        applicationLogger = (Logger) LoggerFactory.getLogger("uk.appsthatmatter.koonj");
        appender = new UnsynchronizedAppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                var record = logger.logRecordBuilder().setTimestamp(event.getInstant()).setContext(Context.current())
                        .setSeverity(switch (event.getLevel().toInt()) {
                            case ch.qos.logback.classic.Level.ERROR_INT -> Severity.ERROR;
                            case ch.qos.logback.classic.Level.WARN_INT -> Severity.WARN;
                            case ch.qos.logback.classic.Level.DEBUG_INT -> Severity.DEBUG;
                            case ch.qos.logback.classic.Level.TRACE_INT -> Severity.TRACE;
                            default -> Severity.INFO;
                        }).setSeverityText(event.getLevel().toString()).setBody(event.getFormattedMessage())
                        .setAttribute("logger.name", event.getLoggerName());
                if (event.getThrowableProxy() != null) {
                    record.setAttribute("exception.type", event.getThrowableProxy().getClassName());
                }
                record.emit();
            }
        };
        appender.setContext(applicationLogger.getLoggerContext());
        appender.setName("KOONJ_OTLP_LOGS");
        appender.start();
        applicationLogger.addAppender(appender);
    }

    @PreDestroy
    void stop() {
        if (appender == null) return;
        applicationLogger.detachAppender(appender);
        appender.stop();
        provider.close();
    }
}
