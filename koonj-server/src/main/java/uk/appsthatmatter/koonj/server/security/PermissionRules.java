package uk.appsthatmatter.koonj.server.security;

public final class PermissionRules {
    private static final String CHECK = "@rolePermissions.allows(authentication, '";
    private static final String END = "')";
    public static final String CATALOG_VIEW = CHECK + "CATALOG_VIEW" + END;
    public static final String CATALOG_OPERATE = CHECK + "CATALOG_OPERATE" + END;
    public static final String TRANSFER_RUN = CHECK + "TRANSFER_RUN" + END;
    public static final String TRANSFER_PURGE = CHECK + "TRANSFER_PURGE" + END;
    public static final String ACTIVITY_VIEW = CHECK + "ACTIVITY_VIEW" + END;
    public static final String ACTIVITY_CONTROL = CHECK + "ACTIVITY_CONTROL" + END;
    public static final String STORAGE_VIEW = CHECK + "STORAGE_VIEW" + END;
    public static final String STORAGE_MANAGE = CHECK + "STORAGE_MANAGE" + END;
    public static final String POLICY_MANAGE = CHECK + "POLICY_MANAGE" + END;
    public static final String OBSERVABILITY_VIEW = CHECK + "OBSERVABILITY_VIEW" + END;
    public static final String INTEGRATIONS_MANAGE = CHECK + "INTEGRATIONS_MANAGE" + END;
    public static final String PROJECTS_MANAGE = CHECK + "PROJECTS_MANAGE" + END;

    public static final String CATALOG_SCAN = CATALOG_OPERATE + " or " + STORAGE_MANAGE;
    public static final String POLICY_READ = CATALOG_VIEW + " or " + POLICY_MANAGE;
    public static final String ARCHIVE = TRANSFER_RUN + " and (#request.archiveMode().name() != 'PURGE' or " + TRANSFER_PURGE + ")";
    public static final String JOB_CONTROL = ACTIVITY_VIEW + " and " + ACTIVITY_CONTROL;

    private PermissionRules() {}
}
