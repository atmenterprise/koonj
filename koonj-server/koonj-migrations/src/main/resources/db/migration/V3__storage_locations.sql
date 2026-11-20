CREATE TABLE storage_locations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    type text NOT NULL CHECK (type IN ('LOCAL', 'SMB', 'NAS', 'S3')),
    name text NOT NULL,
    root_id bigint UNIQUE REFERENCES storage_root(id),
    root_path text,
    bucket_name text,
    aws_region text,
    mount_status text NOT NULL DEFAULT 'UNKNOWN' CHECK (mount_status IN ('ONLINE', 'OFFLINE', 'UNKNOWN')),
    total_capacity_bytes bigint CHECK (total_capacity_bytes >= 0),
    used_bytes bigint CHECK (used_bytes >= 0),
    CHECK ((type = 'S3' AND bucket_name IS NOT NULL AND aws_region IS NOT NULL AND root_path IS NULL)
        OR (type <> 'S3' AND root_path IS NOT NULL AND bucket_name IS NULL AND aws_region IS NULL)),
    UNIQUE (bucket_name, aws_region)
);
INSERT INTO storage_locations(type, name, root_id, root_path)
SELECT 'LOCAL', name, id, mount_path FROM storage_root;

ALTER TABLE file_operation ADD COLUMN target_location_id uuid REFERENCES storage_locations(id);
ALTER TABLE file_generation ADD COLUMN archive_location_id uuid REFERENCES storage_locations(id);
