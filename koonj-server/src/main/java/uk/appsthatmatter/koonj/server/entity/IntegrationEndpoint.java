package uk.appsthatmatter.koonj.server.entity;

import uk.appsthatmatter.koonj.server.security.ProjectScope;
import org.hibernate.annotations.ColumnDefault;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

@Entity
@Getter
@Setter
public class IntegrationEndpoint {
    public enum Kind { API, MCP }

    @Id @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    @ColumnDefault("'" + ProjectScope.LEGACY_VALUE + "'")
    private UUID projectId = ProjectScope.LEGACY;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Kind kind;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false, length = 2048)
    private String url;
    private boolean enabled;
}
