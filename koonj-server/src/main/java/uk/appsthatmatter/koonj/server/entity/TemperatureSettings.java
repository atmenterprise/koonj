package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class TemperatureSettings {
    @Id
    private int id;
    private int hotDays;
    private int coldDays;
}
