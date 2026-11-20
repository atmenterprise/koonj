package uk.appsthatmatter.koonj.api;

import java.util.Map;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/scans")
class ScanController {
    private final JdbcClient jdbc;

    ScanController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Command(long rootId) {}

    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public Map<String, Long> start(@RequestBody Command command) {
        try {
            long id = jdbc.sql("""
                    INSERT INTO scan (root_id, state)
                    SELECT id, 'QUEUED' FROM storage_root WHERE id = :root
                    RETURNING id
                    """).param("root", command.rootId()).query(Long.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown root"));
            return Map.of("scanId", id);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Root already has an active scan");
        }
    }

    @GetMapping("/{id}")
    Map<String, Object> status(@PathVariable("id") long id) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT id, root_id, state, files_seen, errors_seen, last_error, updated_at
                FROM scan WHERE id = :id
                """).param("id", id).query().listOfRows();
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown scan");
        return rows.getFirst();
    }
}
