package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import uk.appsthatmatter.koonj.server.security.Permission;

import java.util.Set;

@Entity
@Getter
@NoArgsConstructor
public class RolePermissionSettings {
    @Id
    @Column(length = 40)
    private String role;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Set<Permission> permissions;

    public RolePermissionSettings(String role, Set<Permission> permissions) {
        this.role = role;
        this.permissions = Set.copyOf(permissions);
    }
}
