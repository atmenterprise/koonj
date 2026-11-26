-- Run once after project-workspaces.sql. See docs/PRIVATE-PROJECTS.md.
BEGIN;
LOCK TABLE project, project_account, project_membership IN ACCESS EXCLUSIVE MODE;
CREATE TEMP TABLE IF NOT EXISTS project_admin_mapping (
    project_id uuid NOT NULL, subject varchar(255) NOT NULL, personal boolean NOT NULL DEFAULT false,
    PRIMARY KEY(project_id, subject)
);
ALTER TABLE project ADD COLUMN generated_name varchar(100) UNIQUE;
ALTER TABLE project_account ADD COLUMN default_project_id uuid UNIQUE REFERENCES project(id);
ALTER TABLE project_membership ADD COLUMN role varchar(16) NOT NULL DEFAULT 'MEMBER'
    CHECK (role IN ('ADMIN', 'MEMBER'));

DO $$
BEGIN
    IF EXISTS (SELECT FROM project_admin_mapping a LEFT JOIN project_membership m
        ON (a.project_id, a.subject) = (m.project_id, m.account_subject) WHERE m.id IS NULL) THEN
        RAISE EXCEPTION 'Administrator mapping must reference existing memberships';
    END IF;
    IF EXISTS (SELECT FROM project_membership m WHERE NOT EXISTS (
        SELECT FROM project_admin_mapping a WHERE a.project_id = m.project_id)) THEN
        RAISE EXCEPTION 'Supply an administrator mapping for each existing occupied project';
    END IF;
    IF EXISTS (SELECT FROM project_admin_mapping WHERE personal GROUP BY subject HAVING count(*) > 1)
       OR EXISTS (SELECT FROM project_admin_mapping WHERE personal AND project_id = '00000000-0000-0000-0000-000000000001') THEN
        RAISE EXCEPTION 'Invalid personal default mapping';
    END IF;
END $$;
UPDATE project_membership m SET role = 'ADMIN' FROM project_admin_mapping a
WHERE (a.project_id, a.subject) = (m.project_id, m.account_subject);
UPDATE project_account p SET default_project_id = a.project_id FROM project_admin_mapping a
WHERE p.subject = a.subject AND a.personal;

DO $$
DECLARE
    account record;
    personal_id uuid;
    personal_name text;
BEGIN
    FOR account IN SELECT subject FROM project_account WHERE default_project_id IS NULL LOOP
        LOOP
            personal_id := gen_random_uuid();
            personal_name := 'Silver Crane Workspace ' || left(personal_id::text, 8);
            INSERT INTO project(id, name, generated_name) VALUES(personal_id, personal_name, personal_name)
                ON CONFLICT DO NOTHING;
            EXIT WHEN FOUND;
        END LOOP;
        UPDATE project_account SET default_project_id = personal_id WHERE subject = account.subject;
        INSERT INTO project_membership(id, project_id, account_subject, role)
            VALUES(gen_random_uuid(), personal_id, account.subject, 'ADMIN');
    END LOOP;
END $$;

CREATE TABLE project_invitation (
    id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES project(id),
    email varchar(320) NOT NULL, inviter_subject varchar(255) NOT NULL REFERENCES project_account(subject),
    inviter_name varchar(255) NOT NULL, project_name varchar(255) NOT NULL,
    role varchar(16) NOT NULL CHECK (role = 'MEMBER'),
    expires_at timestamptz NOT NULL, token_digest varchar(64) UNIQUE, delivery varchar(255) NOT NULL,
    attempts integer NOT NULL, send_after timestamptz, UNIQUE(project_id,email)
);
CREATE INDEX idx_invitation_mail ON project_invitation(send_after) WHERE send_after IS NOT NULL;
COMMIT;
