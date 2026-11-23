package uk.appsthatmatter.koonj.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, UUID> {
    @Query(value = """
            select * from notification_outbox order by created_at, id
            limit :batchSize for update skip locked
            """, nativeQuery = true)
    List<NotificationOutbox> claim(int batchSize);
}
