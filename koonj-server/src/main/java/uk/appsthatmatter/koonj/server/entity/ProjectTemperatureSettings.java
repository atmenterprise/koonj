package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

@Entity
@Getter
@Setter
public class ProjectTemperatureSettings {
    @Id
    private UUID projectId;
    private int hotDays;
    private int coldDays;
}
