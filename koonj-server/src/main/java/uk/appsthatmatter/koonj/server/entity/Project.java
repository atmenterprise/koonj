package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {
    @Id private UUID id;
    @Column(nullable = false, length = 100) private String name;
    @Column(unique = true, length = 100) private String generatedName;

    @Setter private UUID invitationChannelId;

    public Project(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public static Project personal(String name) {
        var project = new Project(UUID.randomUUID(), name);
        project.generatedName = name;
        return project;
    }
}
