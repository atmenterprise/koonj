package uk.appsthatmatter.koonj.notification;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRuleRepository extends JpaRepository<NotificationRule, UUID> {
    List<NotificationRule> findByEventTypeAndEnabledTrue(NotificationEvent.Type type);
    boolean existsByEventTypeAndEnabledTrue(NotificationEvent.Type type);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from NotificationRule r where r.id = :id")
    Optional<NotificationRule> lock(UUID id);
}
