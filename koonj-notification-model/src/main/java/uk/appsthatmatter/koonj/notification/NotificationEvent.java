package uk.appsthatmatter.koonj.notification;

import java.util.Map;

public record NotificationEvent(Type type, String key, String actorId, String actorEmail, String title,
                                Map<String, Object> details) {
    public enum Type { LARGE_BATCH_COMPLETED, TRANSFER_FAILED, QUOTA_THRESHOLD, LICENSE_ALERT }

    public boolean system() {
        return type == Type.QUOTA_THRESHOLD || type == Type.LICENSE_ALERT;
    }

    public long number(String field) {
        return ((Number) details.get(field)).longValue();
    }

    public String summary() {
        return title + "\n" + detailText();
    }

    public String detailText() {
        var text = new java.util.StringJoiner("\n");
        details.forEach((label, value) -> text.add(label + ": " + value));
        return text.toString();
    }

}
