package uk.appsthatmatter.koonj.server.security;

public enum Permission {
    CATALOG_VIEW("View the Catalog", "Browse files, lineage and favorites."),
    CATALOG_OPERATE("Edit and scan the Catalog", "Edit metadata and scan individual locations."),
    TRANSFER_RUN("Archive and recall", "Start archive and recall jobs."),
    TRANSFER_PURGE("Replace local files with links", "Use purge mode when archiving."),
    ACTIVITY_VIEW("View personal activity", "See your jobs, execution logs and file results."),
    ACTIVITY_CONTROL("Control personal activity", "Pause, stop, resume or restart your jobs."),
    STORAGE_VIEW("View Storage status", "See connectivity, usage, capacity and savings."),
    STORAGE_MANAGE("Manage storage connections", "Add, edit, remove, test or scan connections."),
    POLICY_MANAGE("Manage policies", "Configure file temperature thresholds."),
    OBSERVABILITY_VIEW("View observability", "See server health, metrics, logs and trace links."),
    INTEGRATIONS_MANAGE("Manage integrations", "Configure APIs and MCP servers."),
    PROJECTS_MANAGE("Manage projects and members", "Create projects and change access to any project."),
    PERMISSIONS_MANAGE("Manage role permissions", "Configure application-wide permissions for each role.");

    private final String label;
    private final String description;

    Permission(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() { return label; }
    public String description() { return description; }
}
