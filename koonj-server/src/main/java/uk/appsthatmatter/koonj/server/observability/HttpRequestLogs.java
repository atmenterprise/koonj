package uk.appsthatmatter.koonj.server.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

@Component
final class HttpRequestLogs implements ObservationHandler<ServerRequestObservationContext> {
    private static final Logger LOG = LoggerFactory.getLogger(HttpRequestLogs.class);

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ServerRequestObservationContext;
    }

    @Override
    public void onStart(ServerRequestObservationContext context) {
        context.put(HttpRequestLogs.class, System.nanoTime());
    }

    @Override
    public void onStop(ServerRequestObservationContext context) {
        var request = context.getCarrier();
        var response = context.getResponse();
        if (request == null || response == null) return;
        int status = response.getStatus();
        String route = context.getPathPattern();
        var entry = status >= 500 ? LOG.atError() : status >= 400 ? LOG.atWarn()
                : route != null && route.startsWith("/actuator/health") ? LOG.atTrace() : LOG.atInfo();
        long startedAt = context.getRequired(HttpRequestLogs.class);
        entry.log("HTTP {} {} completed with status {} in {} ms", request.getMethod(),
                route == null ? "(unmapped)" : route, status, (System.nanoTime() - startedAt) / 1_000_000);
    }
}
