package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Project {
    @Id private UUID id;
    @Column(nullable = false, length = 100) private String name;
}
