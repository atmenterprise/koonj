# Upgrade to private projects

Back up PostgreSQL and stop the API and notification workers. Apply the existing project-workspaces migration first. Export the current memberships for review.

```sql
SELECT p.id, p.name, m.account_subject, a.display_name
FROM project p JOIN project_membership m ON m.project_id = p.id
JOIN project_account a ON a.subject = m.account_subject ORDER BY p.id;
```

In one psql session, create the temporary mapping below and insert operator-reviewed rows for at least one existing member of each occupied project. Mark a row personal only when you can confirm the user's original personal project. Keep the shared legacy project's personal flag false. Empty projects retain their IDs and data.

```sql
CREATE TEMP TABLE project_admin_mapping (
    project_id uuid NOT NULL, subject varchar(255) NOT NULL, personal boolean NOT NULL DEFAULT false,
    PRIMARY KEY(project_id, subject)
);
-- INSERT INTO project_admin_mapping VALUES ('<project UUID>', '<exact account subject>', false);
\set ON_ERROR_STOP on
\i deploy/migrations/private-projects.sql
```

Every unmapped personal default is provisioned during migration. Verify the following query returns zero rows before restarting services.

```sql
SELECT a.subject FROM project_account a WHERE NOT EXISTS (
    SELECT FROM project_membership m WHERE m.project_id = a.default_project_id
    AND m.account_subject = a.subject AND m.role = 'ADMIN'
);
```

Set **Koonj Administrators** as the Keycloak registration default group and confirm it grants `KOONJ_ADMIN`. Configure Keycloak SMTP and email verification for new email accounts.

After both the private projects and notifications migrations, apply [project-invitation-channels.sql](../deploy/migrations/project-invitation-channels.sql) before starting the upgraded server and worker. Set `KOONJ_PUBLIC_UI_URL` to the browser-accessible HTTPS UI origin on the worker.

In **Projects → Email invitations**, select an enabled email channel or use **Configure email channel** to create one and select it for this project. The worker uses the channel’s SMTP relay, credentials, TLS settings and sender. Fields left blank follow the email channel’s deployment-default rules. Existing pending deliveries without a channel require a resend after selection. Previously delivered links retain their expiry.

Before rollout, sign in as two test users and confirm each project rejects the other user's ID. Send an invitation to a test mailbox and accept it using the recipient's verified account.
