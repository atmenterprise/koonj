package uk.appsthatmatter.koonj.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_delivery_logs", indexes = {
        @Index(name = "idx_notification_delivery_created", columnList = "created_at"),
        @Index(name = "idx_notification_delivery_publish", columnList = "publish_after, id"),
        @Index(name = "idx_notification_delivery_cooldown", columnList = "rule_id, channel_id, dedupe_key, created_at")})
@Getter
@Setter
public class NotificationDelivery {
    public enum Status { PENDING, SENT, FAILED, SUPPRESSED_OPT_OUT }

    @Id @GeneratedValue
    private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private NotificationEvent.Type eventType;
    @Column(nullable = false)
    private UUID channelId;
    private UUID ruleId;
    @Column(length = 255)
    private String dedupeKey;
    @Column(length = 320)
    private String recipient;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Status status = Status.PENDING;
    @Column(nullable = false)
    private int attemptCount;
    @Column(length = 500)
    private String errorMessage;
    @Column(nullable = false)
    private boolean testDelivery;
    @Enumerated(EnumType.STRING)
    private NotificationChannel.Type transportType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb")
    private NotificationEvent payload;
    private UUID dispatchToken;
    private Instant publishAfter;
    private UUID leaseToken;
    @CreationTimestamp @Column(nullable = false)
    private Instant createdAt;
}
