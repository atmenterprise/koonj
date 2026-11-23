package uk.appsthatmatter.koonj.server.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import uk.appsthatmatter.koonj.server.security.KoonjRoles;
import uk.appsthatmatter.koonj.server.security.Permission;
import uk.appsthatmatter.koonj.server.security.RolePermissions;

import java.util.Set;

@RestController
@RequestMapping("/api/v1")
public class RolePermissionsController {
    private final RolePermissions permissions;

    public RolePermissionsController(RolePermissions permissions) { this.permissions = permissions; }

    public record Update(@NotNull Set<@NotNull Permission> permissions) {}

    @GetMapping("/permissions/me")
    public Set<Permission> effective(Authentication authentication) { return permissions.effective(authentication); }

    @GetMapping("/admin/permissions")
    @PreAuthorize(KoonjRoles.ADMIN_ONLY)
    public RolePermissions.Matrix matrix() { return permissions.matrix(); }

    @PutMapping("/admin/permissions/{role}")
    @PreAuthorize(KoonjRoles.ADMIN_ONLY)
    public RolePermissions.RoleMapping update(@PathVariable String role, @Valid @RequestBody Update update) {
        return permissions.update(role, update.permissions());
    }
}
