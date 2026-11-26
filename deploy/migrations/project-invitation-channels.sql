-- Apply after private-projects.sql and notifications.sql with the server and worker stopped.
BEGIN;
ALTER TABLE project ADD COLUMN invitation_channel_id uuid REFERENCES notification_channels(id) ON DELETE SET NULL;
ALTER TABLE project_invitation ADD COLUMN channel_id uuid REFERENCES notification_channels(id) ON DELETE SET NULL;
UPDATE project_invitation SET delivery = 'FAILED', send_after = NULL WHERE send_after IS NOT NULL;
COMMIT;
