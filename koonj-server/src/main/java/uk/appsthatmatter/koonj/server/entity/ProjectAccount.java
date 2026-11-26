package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class ProjectAccount {
    @Id @Column(length = 255) private String subject;
    @Column(nullable = false, length = 255) private String displayName;
    @Column(length = 255) private String email;
    @OneToOne @JoinColumn(unique = true) private Project defaultProject;
}
