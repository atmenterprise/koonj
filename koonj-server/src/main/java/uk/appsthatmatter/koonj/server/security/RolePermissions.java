package uk.appsthatmatter.koonj.server.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import uk.appsthatmatter.koonj.server.entity.RolePermissionSettings;
import uk.appsthatmatter.koonj.server.repository.RolePermissionSettingsRepository;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static uk.appsthatmatter.koonj.server.security.Permission.*;

@Service
public class RolePermissions {
    private static final List<String> ROLES = List.of(KoonjRoles.ADMIN, KoonjRoles.OPERATOR, KoonjRoles.VIEWER);
    private static final Set<Permission> ADMIN = Set.copyOf(EnumSet.allOf(Permission.class));
    private static final Map<String, Set<Permission>> DEFAULTS = Map.of(
            KoonjRoles.ADMIN, ADMIN,
            KoonjRoles.OPERATOR, Set.of(CATALOG_VIEW, CATALOG_OPERATE, TRANSFER_RUN, ACTIVITY_VIEW,
                    ACTIVITY_CONTROL, STORAGE_VIEW),
            KoonjRoles.VIEWER, Set.of(CATALOG_VIEW));
    private final RolePermissionSettingsRepository settings;
    private final String agentClientId;

    public RolePermissions(RolePermissionSettingsRepository settings,
                           @Value("${KOONJ_AGENT_CLIENT_ID:koonj-agent}") String agentClientId) {
        this.settings = settings;
        this.agentClientId = agentClientId;
    }

    public record Definition(Permission id, String label, String description) {}
    public record RoleMapping(String role, boolean editable, Set<Permission> permissions) {}
    public record Matrix(List<Definition> permissions, List<RoleMapping> roles) {}

    public boolean allows(Authentication authentication, Permission permission) {
        return effective(authentication).contains(permission);
    }

    public Set<Permission> effective(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return Set.of();
        var roles = ROLES.stream().filter(role -> authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + role).equals(authority.getAuthority()))).toList();
        if (roles.contains(KoonjRoles.ADMIN)) return ADMIN;
        if (roles.isEmpty()) return Set.of();
        // The project interceptor restricts this machine client to the two FUSE endpoints.
        if (authentication instanceof JwtAuthenticationToken jwt && agentClientId.equals(jwt.getToken().getClaimAsString("azp"))) {
            return Set.of(CATALOG_VIEW, TRANSFER_RUN);
        }
        var result = EnumSet.noneOf(Permission.class);
        var configured = configured(roles);
        for (String role : roles) result.addAll(configured.getOrDefault(role, DEFAULTS.get(role)));
        return result;
    }

    public Matrix matrix() {
        var configured = configured(ROLES);
        return new Matrix(Arrays.stream(Permission.values()).map(p -> new Definition(p, p.label(), p.description())).toList(),
                ROLES.stream().map(role -> mapping(role, configured.getOrDefault(role, DEFAULTS.get(role)))).toList());
    }

    public RoleMapping update(String role, Set<Permission> permissions) {
        if (!ROLES.contains(role)) throw new IllegalArgumentException("Unknown Koonj role");
        if (KoonjRoles.ADMIN.equals(role) || permissions.contains(PERMISSIONS_MANAGE)) {
            throw new IllegalArgumentException("Only administrators can manage role permissions, and administrator grants are fixed");
        }
        return mapping(role, settings.save(new RolePermissionSettings(role, permissions)).getPermissions());
    }

    private Map<String, Set<Permission>> configured(List<String> roles) {
        return settings.findAllById(roles).stream()
                .collect(Collectors.toMap(RolePermissionSettings::getRole, RolePermissionSettings::getPermissions));
    }

    private static RoleMapping mapping(String role, Set<Permission> grants) {
        return new RoleMapping(role, !KoonjRoles.ADMIN.equals(role), grants);
    }
}
