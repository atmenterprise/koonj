package uk.appsthatmatter.koonj.api;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/storage/buckets")
class BucketController {
    record Bucket(UUID id, String bucketName, String region) {}
    record Registration(String bucketName, String region) {}
    record Settings(List<Bucket> buckets, boolean legacyMappingRequired) {}
    record LegacySelection(UUID locationId) {}

    private final JdbcClient jdbc;

    BucketController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    Settings settings() {
        var buckets = jdbc.sql("""
                SELECT id, bucket_name, aws_region FROM storage_locations
                WHERE type = 'S3'
                ORDER BY bucket_name, aws_region
                """)
                .query((rs, i) -> new Bucket(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
        boolean needsMapping = jdbc.sql("""
                SELECT NOT EXISTS (SELECT 1 FROM legacy_archive_binding)
                    AND (EXISTS (SELECT 1 FROM file_generation WHERE object_key IS NOT NULL AND archive_location_id IS NULL)
                        OR EXISTS (SELECT 1 FROM file_operation WHERE kind = 'ARCHIVE' AND state IN ('QUEUED','RUNNING')
                            AND target_location_id IS NULL))
                """).query(Boolean.class).single();
        return new Settings(buckets, needsMapping);
    }

    @PostMapping(value = "/legacy-binding", consumes = "application/json")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @org.springframework.transaction.annotation.Transactional
    public void confirmLegacy(@RequestBody LegacySelection selection) {
        if (selection.locationId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        boolean registered = jdbc.sql("SELECT EXISTS (SELECT 1 FROM storage_locations WHERE id = :id AND type = 'S3')")
                .param("id", selection.locationId()).query(Boolean.class).single();
        if (!registered) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        jdbc.sql("""
                INSERT INTO legacy_archive_binding(location_id) VALUES (:id)
                ON CONFLICT (singleton) DO NOTHING
                """).param("id", selection.locationId()).update();
        UUID bound = jdbc.sql("SELECT location_id FROM legacy_archive_binding").query(UUID.class).single();
        if (!bound.equals(selection.locationId())) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "The legacy bucket has already been confirmed");
        jdbc.sql("""
                UPDATE file_generation SET archive_location_id = :id
                WHERE object_key IS NOT NULL AND archive_location_id IS NULL
                """).param("id", bound).update();
        jdbc.sql("""
                UPDATE file_operation SET target_location_id = :id WHERE kind = 'ARCHIVE'
                    AND target_location_id IS NULL AND state IN ('QUEUED','RUNNING')
                """).param("id", bound).update();
    }

    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    Bucket register(@RequestBody Registration command) {
        String bucket = command.bucketName();
        String region = command.region();
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || bucket.contains("..") || bucket.matches("[0-9]+(\\.[0-9]+){3}")
                || region == null || !region.matches("[a-z][a-z0-9]*(-[a-z0-9]+)+-[0-9]+")
                || region.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid bucket name or AWS region");
        }
        try {
            UUID id = jdbc.sql("""
                    INSERT INTO storage_locations(type, name, bucket_name, aws_region)
                    VALUES ('S3', :bucket, :bucket, :region) RETURNING id
                    """).param("bucket", bucket).param("region", region).query(UUID.class).single();
            return new Bucket(id, bucket, region);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bucket already registered in this region");
        }
    }
}
