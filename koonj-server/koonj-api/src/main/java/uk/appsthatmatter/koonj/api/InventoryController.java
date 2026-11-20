package uk.appsthatmatter.koonj.api;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.core.FileLifecycle;

@RestController
@RequestMapping("/api")
class InventoryController {
    private final JdbcClient jdbc;

    InventoryController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Page(List<Map<String, Object>> items, String nextCursor, String previousCursor) {}
    record Command(long generationId, FileLifecycle.Operation operation, java.util.UUID targetLocationId) {}

    @GetMapping("/inventory")
    Page inventory(@RequestParam("rootId") long root, @RequestParam(name = "limit", defaultValue = "100") int limit,
                   @RequestParam(name = "cursor", required = false) String cursor) {
        if (limit < 1 || limit > 200) throw badRequest("Limit must be between 1 and 200");
        long after = cursor == null ? 0 : decode(root, cursor);
        boolean reverse = after < 0;
        String order = reverse ? "f.id < :after ORDER BY f.id DESC" : "f.id > :after ORDER BY f.id";
        var rows = jdbc.sql("""
                SELECT f.id, f.relative_path, g.id AS generation_id, g.size_bytes, g.modified_at, g.state
                FROM inventory_file f
                JOIN LATERAL (SELECT id, size_bytes, modified_at, state FROM file_generation
                    WHERE file_id = f.id ORDER BY id DESC LIMIT 1) g ON true
                WHERE f.root_id = :root AND
                """ + order + " LIMIT :count").param("root", root).param("after", Math.abs(after))
                .param("count", limit + 1).query().listOfRows();
        if (rows.isEmpty()) return new Page(List.of(), null, null);
        boolean more = rows.size() > limit;
        var page = rows.subList(0, Math.min(rows.size(), limit));
        if (reverse) page = page.reversed();
        long first = ((Number) page.getFirst().get("id")).longValue();
        long last = ((Number) page.getLast().get("id")).longValue();
        return new Page(page, (reverse || more) ? encode(root, last) : null,
                (reverse ? more : cursor != null) ? encode(root, -first) : null);
    }

    private static String encode(long root, long boundary) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(16)
                .putLong(root).putLong(boundary).array());
    }

    @GetMapping("/roots")
    List<Map<String, Object>> roots() {
        return jdbc.sql("SELECT id, name, managed FROM storage_root ORDER BY name").query().listOfRows();
    }

    private static long decode(long root, String cursor) {
        if (cursor.length() != 22) throw badRequest("Invalid inventory cursor");
        try {
            ByteBuffer key = ByteBuffer.wrap(Base64.getUrlDecoder().decode(cursor));
            if (key.getLong() != root) throw badRequest("Cursor belongs to another root");
            long id = key.getLong();
            if (id == 0 || id == Long.MIN_VALUE) throw badRequest("Invalid inventory cursor");
            return id;
        } catch (IllegalArgumentException ex) {
            throw badRequest("Invalid inventory cursor");
        }
    }

    @PostMapping(value = "/operations", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Long> submit(@RequestBody Command command) {
        if (command.operation() == null || command.generationId() < 1) throw badRequest("Invalid operation");
        if (command.operation() == FileLifecycle.Operation.ARCHIVE) {
            if (command.targetLocationId() == null) throw badRequest("Select an archive bucket");
            boolean registered = jdbc.sql("SELECT EXISTS (SELECT 1 FROM storage_locations WHERE id = :id AND type = 'S3')")
                    .param("id", command.targetLocationId()).query(Boolean.class).single();
            if (!registered) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } else if (command.targetLocationId() != null) {
            throw badRequest("Only archive accepts a target location");
        }
        long id = jdbc.sql("""
                INSERT INTO file_operation (generation_id, kind, state, target_location_id)
                SELECT id, :kind, 'QUEUED', :target FROM file_generation WHERE id = :generation
                RETURNING id
                """).param("target", command.targetLocationId(), java.sql.Types.OTHER).param("kind", command.operation().name()).param("generation", command.generationId())
                .query(Long.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return Map.of("operationId", id);
    }

    @GetMapping("/operations/{id}")
    Map<String, Object> status(@PathVariable("id") long id) {
        var rows = jdbc.sql("""
                SELECT o.id, o.generation_id, o.kind, o.state, o.attempts, o.created_at, o.updated_at,
                    o.last_error, f.root_id FROM file_operation o
                JOIN file_generation g ON g.id = o.generation_id JOIN inventory_file f ON f.id = g.file_id
                WHERE o.id = :id
                """).param("id", id).query().listOfRows();
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
