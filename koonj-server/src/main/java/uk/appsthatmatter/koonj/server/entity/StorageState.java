package uk.appsthatmatter.koonj.server.entity;

public enum StorageState {
    LOCAL_ONLY, QUEUED_ARCHIVE, ARCHIVING, ARCHIVED, PURGED_LOCAL, QUEUED_RECALL, RECALLING, FAILED
}
