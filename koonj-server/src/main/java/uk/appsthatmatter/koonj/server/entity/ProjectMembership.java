package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_project_membership", columnNames = {"project_id", "account_subject"}),
       indexes = @Index(name = "idx_project_member_account", columnList = "account_subject"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectMembership {
    @Id @GeneratedValue private UUID id;
    @ManyToOne(optional = false) private Project project;
    @ManyToOne(optional = false) private ProjectAccount account;

    public ProjectMembership(Project project, ProjectAccount account) {
        this.project = project;
        this.account = account;
    }
}
