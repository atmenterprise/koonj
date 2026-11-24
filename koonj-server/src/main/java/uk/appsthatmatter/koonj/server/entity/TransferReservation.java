package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "transfer_reservations", indexes = {
        @Index(name = "idx_transfer_reservation_namespace", columnList = "namespace"),
        @Index(name = "idx_transfer_reservation_job", columnList = "job_id")
})
public class TransferReservation {
    @Id @GeneratedValue
    private UUID id;
    @Column(nullable = false)
    private UUID jobId;
    @Column(nullable = false, length = 64)
    private String namespace;
    @Column(nullable = false, columnDefinition = "text")
    private String path;
    @Column(nullable = false)
    private boolean subtree;

    @Column(nullable = false)
    private boolean scan;

    public TransferReservation(UUID jobId, String namespace, String path, boolean subtree, boolean scan) {
        this.jobId = jobId;
        this.namespace = namespace;
        this.path = path;
        this.subtree = subtree;
        this.scan = scan;
    }
}
