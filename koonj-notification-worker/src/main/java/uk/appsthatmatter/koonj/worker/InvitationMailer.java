package uk.appsthatmatter.koonj.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.appsthatmatter.koonj.notification.ProjectInvitationRepository;
import uk.appsthatmatter.koonj.notification.NotificationChannel;
import uk.appsthatmatter.koonj.notification.NotificationChannelRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Component
class InvitationMailer {
    private final ProjectInvitationRepository invitations;
    private final NotificationTransport transport;
    private final NotificationChannelRepository channels;
    private final String ui;
    private final int maxAttempts;
    private final Duration delay;
    private final SecureRandom random = new SecureRandom();

    InvitationMailer(ProjectInvitationRepository invitations, NotificationTransport transport, NotificationChannelRepository channels,
                     @Value("${KOONJ_PUBLIC_UI_URL:}") String ui,
                     @Value("${koonj.worker.mail.max-attempts}") int maxAttempts,
                     @Value("${koonj.worker.mail.retry-delay}") Duration delay) {
        this.invitations = invitations;
        this.transport = transport;
        this.channels = channels;
        int end = ui.length();
        while (end > 0 && ui.charAt(end - 1) == '/') end--;
        this.ui = ui.substring(0, end);
        this.maxAttempts = maxAttempts;
        this.delay = delay;
    }

    @Transactional
    @Scheduled(fixedDelayString = "${koonj.worker.poll-interval}")
    public void deliver() {
        invitations.claimMail().ifPresent(invite -> {
            var channel = java.util.Optional.ofNullable(invite.getChannelId())
                    .flatMap(id -> channels.findByIdAndTypeAndEnabledTrue(id, NotificationChannel.Type.EMAIL));
            if (ui.isBlank() || channel.isEmpty()) {
                invite.failed(false, maxAttempts, delay);
                return;
            }
            byte[] secret = new byte[32];
            random.nextBytes(secret);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            String body = "%s invited you to join %s on Koonj. Sign in with this email address and accept by %s.\n\n%s/invite#%s"
                    .formatted(invite.getInviterName(), invite.getProjectName(), invite.getExpiresAt(), ui, token);
            var result = transport.invitation(channel.orElseThrow(), invite.getEmail(), body);
            if (result.error() == null) invite.delivered(token);
            else invite.failed(result.retryable(), maxAttempts, delay);
        });
    }
}
