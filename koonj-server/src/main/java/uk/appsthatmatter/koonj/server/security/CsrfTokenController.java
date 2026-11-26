package uk.appsthatmatter.koonj.server.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "koonj.security.basic-auth.enabled", havingValue = "true")
class CsrfTokenController {
    @GetMapping("/api/v1/csrf")
    ResponseEntity<CsrfToken> token(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(token);
    }
}
