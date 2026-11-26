package uk.appsthatmatter.koonj.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "email"}))
public class ProjectInvitation {
    @Id @GeneratedValue private UUID id;
    @Column(nullable = false) private UUID projectId;
    @Column(nullable = false, length = 320) private String email;
    @Column(nullable = false, length = 16, updatable = false) private String role = "MEMBER";
    @Column(nullable = false) private String inviterSubject;
    @Column(nullable = false) private String inviterName;
    @Column(nullable = false) private String projectName;
    @Column(nullable = false) private Instant expiresAt;
    @Column(unique = true, length = 64) private String tokenDigest;
    @Column(nullable = false) private String delivery;
    private Instant sendAfter;
    private int attempts;
    private UUID channelId;

    public ProjectInvitation(UUID projectId, String projectName, String email, UUID channelId, String inviterSubject, String inviterName) {
        this.projectId = projectId;
        this.projectName = projectName;
        this.email = email;
        queue(channelId, inviterSubject, inviterName);
    }

    public void queue(UUID channelId, String subject, String name) {
        this.channelId = channelId;
        inviterSubject = subject;
        inviterName = name;
        delivery = "QUEUED";
        tokenDigest = null;
        attempts = 0;
        sendAfter = Instant.now();
        expiresAt = sendAfter.plus(Duration.ofDays(7));
    }

    public void delivered(String token) {
        tokenDigest = digest(token);
        delivery = "SENT";
        sendAfter = null;
    }

    public void failed(boolean retryable, int limit, Duration delay) {
        attempts++;
        if (retryable && attempts < limit) sendAfter = Instant.now().plus(delay);
        else {
            delivery = "FAILED";
            sendAfter = null;
        }
    }

    public static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
