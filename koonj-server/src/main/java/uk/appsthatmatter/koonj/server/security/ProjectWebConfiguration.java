package uk.appsthatmatter.koonj.server.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.server.repository.ProjectRepository;
import uk.appsthatmatter.koonj.server.service.ProjectService;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Configuration
public class ProjectWebConfiguration implements WebMvcConfigurer {
    private final ProjectService projects;
    private final ProjectRepository repository;

    private final Set<UUID> agentProjects;
    private final String agentClientId;

    public ProjectWebConfiguration(ProjectService projects, ProjectRepository repository,
                                   @Value("${koonj.projects.agent-ids:00000000-0000-0000-0000-000000000001}") String agentIds,
                                   @Value("${KOONJ_AGENT_CLIENT_ID:koonj-agent}") String agentClientId) {
        this.agentClientId = agentClientId;
        this.agentProjects = Arrays.stream(agentIds.split(",")).map(String::strip).filter(id -> !id.isEmpty())
                .map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
        this.projects = projects;
        this.repository = repository;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!(request.getUserPrincipal() instanceof Authentication auth) || "OPTIONS".equals(request.getMethod())) return true;
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if (path.equals("/api/v1/projects") || path.startsWith("/api/v1/projects/")) {
                    if (isAgent(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Human sign-in required");
                    return true;
                }
                if (path.startsWith("/api/v1/notifications/") && !isAgent(auth)) return true;
                UUID requested = parse(request.getHeader(ProjectScope.HEADER));
                UUID project;
                if (request.getAttribute(ProjectScope.ATTRIBUTE) instanceof UUID) return true;
                if (isAgent(auth)) {
                    if (!agentRoute(request)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent access is limited to file recall");
                    project = requested == null ? ProjectScope.LEGACY : requested;
                    if (!agentProjects.contains(project)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent has no access to this project");
                    if (!repository.existsById(project)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project was not found");
                } else {
                    if (!path.equals("/api/v1/permissions/me") && auth.getAuthorities().stream().noneMatch(
                            authority -> KoonjRoles.ALL.stream().anyMatch(role -> ("ROLE_" + role).equals(authority.getAuthority()))))
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace role required");
                    project = projects.authorize(auth, requested);
                }
                request.setAttribute(ProjectScope.ATTRIBUTE, project);
                return true;
            }
        }).addPathPatterns("/api/v1/**");
    }

    private boolean isAgent(Authentication auth) {
        return auth instanceof JwtAuthenticationToken jwt
                && agentClientId.equals(jwt.getToken().getClaimAsString("azp"));
    }

    private static boolean agentRoute(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return ("GET".equals(request.getMethod()) && path.matches("/api/v1/catalog/files/[^/]+"))
                || ("POST".equals(request.getMethod()) && "/api/v1/transfers/recall/sync".equals(path));
    }

    private static UUID parse(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid project identifier");
        }
    }
}
