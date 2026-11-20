package uk.appsthatmatter.koonj.server.security;

import java.util.Set;

public final class KoonjRoles {
    public static final String ADMIN = "KOONJ_ADMIN";
    public static final String OPERATOR = "KOONJ_OPERATOR";
    public static final String VIEWER = "KOONJ_VIEWER";
    public static final Set<String> ALL = Set.of(ADMIN, OPERATOR, VIEWER);

    public static final String ADMIN_ONLY = "hasRole('" + ADMIN + "')";
    public static final String READ = "hasAnyRole('" + ADMIN + "', '" + OPERATOR + "', '" + VIEWER + "')";
    public static final String OPERATE = "hasAnyRole('" + ADMIN + "', '" + OPERATOR + "')";
    public static final String ARCHIVE = OPERATE + " and (!#request.purgeSource() or " + ADMIN_ONLY + ")";

    private KoonjRoles() {}
}
