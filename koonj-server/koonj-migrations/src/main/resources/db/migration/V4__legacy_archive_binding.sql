CREATE TABLE legacy_archive_binding (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    location_id uuid NOT NULL REFERENCES storage_locations(id),
    confirmed_by text NOT NULL,
    confirmed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX legacy_unmapped_generations_idx ON file_generation (id)
    WHERE object_key IS NOT NULL AND archive_location_id IS NULL;
