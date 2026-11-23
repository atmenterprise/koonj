package uk.appsthatmatter.koonj.server.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

public final class ProjectScope {
    public static final String HEADER = "X-Koonj-Project";
    public static final String LEGACY_VALUE = "00000000-0000-0000-0000-000000000001";
    public static final UUID LEGACY = UUID.fromString(LEGACY_VALUE);
    static final String ATTRIBUTE = ProjectScope.class.getName();
    private static final ThreadLocal<UUID> WORKER = new ThreadLocal<>();

    private ProjectScope() {}

    public static UUID id() {
        UUID worker = WORKER.get();
        if (worker != null) return worker;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getRequest().getAttribute(ATTRIBUTE) instanceof UUID project) return project;
        return LEGACY;
    }

    public static boolean includes(UUID projectId) {
        return id().equals(projectId);
    }

    public static void require(UUID projectId, String message) {
        if (!includes(projectId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    public static void run(UUID projectId, Runnable work) {
        UUID previous = WORKER.get();
        WORKER.set(projectId);
        try {
            work.run();
        } finally {
            if (previous == null) WORKER.remove();
            else WORKER.set(previous);
        }
    }
}
