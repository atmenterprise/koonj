package uk.appsthatmatter.koonj.server.notification;

import uk.appsthatmatter.koonj.notification.EmailAddress;
import uk.appsthatmatter.koonj.notification.UserNotificationPreference;
import uk.appsthatmatter.koonj.notification.UserNotificationPreferenceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import uk.appsthatmatter.koonj.server.security.JobActor;
import uk.appsthatmatter.koonj.notification.NotificationEvent.Type;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/notifications/preferences/me")
class NotificationPreferencesController {
    private final UserNotificationPreferenceRepository preferences;

    NotificationPreferencesController(UserNotificationPreferenceRepository preferences) {
        this.preferences = preferences;
    }

    record Settings(@NotNull Boolean emailNotificationsEnabled, @NotNull Set<@NotNull Type> mutedEventTypes,
                    Instant silencedUntil) {
        static Settings from(UserNotificationPreference preference) {
            return new Settings(preference.isEmailEnabled(), preference.getMutedEventTypes(), preference.getSilencedUntil());
        }
    }

    @GetMapping
    public Settings get() {
        return Settings.from(preference());
    }

    @PutMapping
    @Transactional
    public Settings put(@Valid @RequestBody Settings settings) {
        var preference = preference();
        String email = JobActor.verifiedEmail();
        preference.setVerifiedEmail(EmailAddress.valid(email) ? email.toLowerCase(Locale.ROOT) : null);
        preference.setEmailEnabled(settings.emailNotificationsEnabled());
        preference.setMutedEventTypes(settings.mutedEventTypes());
        preference.setSilencedUntil(settings.silencedUntil());
        return Settings.from(preferences.save(preference));
    }

    private UserNotificationPreference preference() {
        String subject = JobActor.subject();
        return preferences.findById(subject).orElseGet(() -> {
            var preference = new UserNotificationPreference();
            preference.setUserId(subject);
            return preference;
        });
    }
}
