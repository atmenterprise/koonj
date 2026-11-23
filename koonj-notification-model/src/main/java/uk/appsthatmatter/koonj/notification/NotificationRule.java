package uk.appsthatmatter.koonj.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "notification_rules")
@Getter
@Setter
public class NotificationRule {
    public record Thresholds(Long fileCount, Long bytes, Integer failureCount, Integer warningPercent,
                      Integer expiryWarningDays, Integer cooldownMinutes) {
        public boolean matchesBatch(long files, long size) {
            if (fileCount == null && bytes == null) return files >= 1_000 || size >= 10_000_000_000L;
            return (fileCount != null && files >= fileCount) || (bytes != null && size >= bytes);
        }
        public int failures() { return failureCount == null ? 1 : failureCount; }
        public int percent() { return warningPercent == null ? 80 : warningPercent; }
        public int days() { return expiryWarningDays == null ? 30 : expiryWarningDays; }
        public int cooldown() { return cooldownMinutes == null ? 60 : cooldownMinutes; }
    }

    @Id @GeneratedValue
    private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private NotificationEvent.Type eventType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private Thresholds thresholdConfig;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private List<UUID> channelIds;
    @Column(nullable = false)
    private boolean enabled;
}
