package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class ProjectAccount {
    @Id @Column(length = 255) private String subject;
    @Column(nullable = false, length = 255) private String displayName;
    @Column(length = 255) private String email;
    // After personal project deletion the default may be another person's shared project.
    private UUID fallbackProjectId;

    public UUID effectiveDefaultId() {
        return defaultProject == null ? fallbackProjectId : defaultProject.getId();
    }

    @OneToOne @JoinColumn(unique = true) private Project defaultProject;
}
