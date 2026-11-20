package uk.appsthatmatter.koonj.core;

public final class FileLifecycle {
    public enum State {
        LOCAL, ARCHIVED, TIERED
    }

    public enum Operation {
        ARCHIVE, TIER, RECALL
    }

    private FileLifecycle() {
    }

    public static State after(State current, Operation operation) {
        return switch (operation) {
            case ARCHIVE -> transition(current, State.LOCAL, State.ARCHIVED);
            case TIER -> transition(current, State.ARCHIVED, State.TIERED);
            case RECALL -> transition(current, State.TIERED, State.ARCHIVED);
        };
    }

    private static State transition(State current, State expected, State result) {
        if (current != expected) {
            throw new IllegalStateException("Expected " + expected + " but found " + current);
        }
        return result;
    }
}
