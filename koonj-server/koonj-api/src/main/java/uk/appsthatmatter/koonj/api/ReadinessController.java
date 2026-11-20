package uk.appsthatmatter.koonj.api;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReadinessController {
    private final JdbcClient jdbc;

    ReadinessController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health/ready")
    void ready() {
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM file_operation LIMIT 1)").query(Boolean.class).single();
    }
}
