package uk.appsthatmatter.koonj.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, UUID> {
    java.util.List<NotificationChannel> findByTypeAndEnabledTrueOrderByNameAscIdAsc(NotificationChannel.Type type);
    java.util.Optional<NotificationChannel> findByIdAndTypeAndEnabledTrue(UUID id, NotificationChannel.Type type);
}
