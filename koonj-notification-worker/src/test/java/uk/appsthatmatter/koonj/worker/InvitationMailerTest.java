package uk.appsthatmatter.koonj.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uk.appsthatmatter.koonj.notification.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InvitationMailerTest {
    @Test
    void stopsDeliveryWhenSelectedChannelIsDisabledOrDeleted() {
        var invite = new ProjectInvitation(UUID.randomUUID(), "Archive", "bob@example.test", UUID.randomUUID(), "alice", "Alice");
        var invitations = mock(ProjectInvitationRepository.class);
        when(invitations.claimMail()).thenReturn(Optional.of(invite));
        var transport = mock(NotificationTransport.class);
        var channels = mock(NotificationChannelRepository.class);
        new InvitationMailer(invitations, transport, channels, "https://ui.example.test", 3, Duration.ofSeconds(1)).deliver();

        verify(channels).findByIdAndTypeAndEnabledTrue(invite.getChannelId(), NotificationChannel.Type.EMAIL);
        verifyNoInteractions(transport);
        assertThat(invite.getDelivery()).isEqualTo("FAILED");
        assertThat(invite.getTokenDigest()).isNull();
    }

    @Test
    void sendsTheRecipientASingleUseSecretAndStoresItsDigest() throws Exception {
        var channel = new NotificationChannel();
        channel.setId(UUID.randomUUID());
        var channels = mock(NotificationChannelRepository.class);
        when(channels.findByIdAndTypeAndEnabledTrue(channel.getId(), NotificationChannel.Type.EMAIL)).thenReturn(Optional.of(channel));
        var invite = new ProjectInvitation(UUID.randomUUID(), "Archive", "bob@example.test", channel.getId(), "alice", "Alice");
        var invitations = mock(ProjectInvitationRepository.class);
        when(invitations.claimMail()).thenReturn(Optional.of(invite));
        try (var smtp = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var received = new CompletableFuture<String>();
            Thread.ofVirtual().start(() -> NotificationWorkerIntegrationTest.acceptMail(smtp, received));
            var timeout = Duration.ofSeconds(3);
            var transport = new NotificationTransport(new ObjectMapper(), timeout, timeout, timeout,
                    "", 1, "SSL", "", "", "");
            channel.setConfig(new NotificationChannel.Config(null, null, null, null, smtp.getInetAddress().getHostAddress(),
                    smtp.getLocalPort(), "NONE", null, null, "koonj@example.test", null, null));
            new InvitationMailer(invitations, transport, channels, "https://ui.example.test", 3, timeout).deliver();
            String mail = received.get(5, TimeUnit.SECONDS);
            assertThat(mail).contains("To: bob@example.test", "Alice invited you to join Archive");
            var link = Pattern.compile("https://ui.example.test/invite#([A-Za-z0-9_-]{43})").matcher(mail);
            assertThat(link.find()).isTrue();
            assertThat(invite.getTokenDigest()).isEqualTo(ProjectInvitation.digest(link.group(1)));
            assertThat(invite.getDelivery()).isEqualTo("SENT");
            assertThat(invite.getSendAfter()).isNull();
        }
    }
}
