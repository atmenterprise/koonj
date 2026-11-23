package uk.appsthatmatter.koonj.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_outbox", indexes = @Index(name = "idx_notification_outbox_created", columnList = "created_at, id"))
@Getter
@Setter
public class NotificationOutbox {
    @Id @GeneratedValue
    private UUID id;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private NotificationEvent event;
    private UUID testChannelId;
    @CreationTimestamp @Column(nullable = false)
    private Instant createdAt;
}
