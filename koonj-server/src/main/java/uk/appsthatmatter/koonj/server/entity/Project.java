package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {
    @Id private UUID id;
    @Column(nullable = false, length = 100) private String name;
    @Column(unique = true, length = 100) private String generatedName;

    @Setter private UUID invitationChannelId;
    private Instant deletedAt;

    public void rename(String name) {
        this.name = name.strip();
    }

    public void markDeleted() {
        deletedAt = Instant.now();
    }

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
