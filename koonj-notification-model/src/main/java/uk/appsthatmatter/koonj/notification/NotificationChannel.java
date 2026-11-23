package uk.appsthatmatter.koonj.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "notification_channels")
@Getter
@Setter
public class NotificationChannel {
    public enum Type { WEBHOOK, SLACK, TEAMS, EMAIL }

    public record Config(String endpoint, Map<String, String> headers, String signingSecret, List<String> adminRecipients,
                  String smtpHost, Integer smtpPort, String smtpTls, String smtpUsername, String smtpPassword,
                  String from, String subjectTemplate, String bodyTemplate) {}

    @Id @GeneratedValue
    private UUID id;
    @Column(nullable = false, length = 120)
    private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Type type;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private Config config;
    @Column(nullable = false)
    private boolean enabled;
    @CreationTimestamp @Column(nullable = false)
    private Instant createdAt;
    @UpdateTimestamp @Column(nullable = false)
    private Instant updatedAt;
}
