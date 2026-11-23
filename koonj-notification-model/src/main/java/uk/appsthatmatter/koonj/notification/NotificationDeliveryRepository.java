package uk.appsthatmatter.koonj.notification;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {
    boolean existsByRuleIdAndChannelIdAndDedupeKeyAndCreatedAtGreaterThanEqual(UUID ruleId, UUID channelId,
                                                                               String dedupeKey, Instant since);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from NotificationDelivery d where d.id = :id")
    Optional<NotificationDelivery> lock(UUID id);

    @Query(value = """
            select * from notification_delivery_logs
            where status = 'PENDING' and publish_after <= :now
            order by publish_after, id limit :batchSize for update skip locked
            """, nativeQuery = true)
    List<NotificationDelivery> claimPublishable(Instant now, int batchSize);

    @Modifying
    @Transactional
    @Query(value = """
            update notification_delivery_logs set status = 'FAILED', error_message = 'Delivery was interrupted before worker upgrade'
            where status = 'PENDING' and dispatch_token is null and created_at < :before
            """, nativeQuery = true)
    void failLegacyPending(Instant before);
}
