package uk.appsthatmatter.koonj.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Set;

@Entity
@Table(name = "user_notification_preferences", indexes = @Index(name = "idx_notification_preference_email", columnList = "verified_email"))
@Getter
@Setter
public class UserNotificationPreference {
    @Id
    private String userId;
    @Column(length = 320)
    private String verifiedEmail;
    @Column(nullable = false)
    private boolean emailEnabled = true;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private Set<NotificationEvent.Type> mutedEventTypes = Set.of();
    private Instant silencedUntil;
    @UpdateTimestamp @Column(nullable = false)
    private Instant updatedAt;

    public boolean suppresses(NotificationEvent.Type type, Instant now) {
        return !emailEnabled || mutedEventTypes.contains(type) || (silencedUntil != null && silencedUntil.isAfter(now));
    }
}
