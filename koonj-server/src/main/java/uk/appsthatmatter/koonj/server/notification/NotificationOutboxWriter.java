package uk.appsthatmatter.koonj.server.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.appsthatmatter.koonj.notification.NotificationEvent;
import uk.appsthatmatter.koonj.notification.NotificationOutbox;
import uk.appsthatmatter.koonj.notification.NotificationOutboxRepository;

import java.util.Map;
import java.util.UUID;

@Service
public class NotificationOutboxWriter {
    private final NotificationOutboxRepository outbox;

    NotificationOutboxWriter(NotificationOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Transactional
    public void enqueue(NotificationEvent event) {
        record(event, null);
    }

    @Transactional
    public void test(UUID channelId, String actorId, String actorEmail) {
        var event = new NotificationEvent(NotificationEvent.Type.LARGE_BATCH_COMPLETED, "test", actorId, actorEmail,
                "Koonj channel test", Map.of("message", "This test was requested from notification settings."));
        record(event, channelId);
    }

    private void record(NotificationEvent event, UUID testChannelId) {
        var row = new NotificationOutbox();
        row.setEvent(event);
        row.setTestChannelId(testChannelId);
        outbox.save(row);
    }
}
