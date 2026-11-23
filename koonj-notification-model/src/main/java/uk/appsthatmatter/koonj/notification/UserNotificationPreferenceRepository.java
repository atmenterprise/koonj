package uk.appsthatmatter.koonj.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserNotificationPreferenceRepository extends JpaRepository<UserNotificationPreference, String> {
    List<UserNotificationPreference> findByVerifiedEmail(String email);
}
