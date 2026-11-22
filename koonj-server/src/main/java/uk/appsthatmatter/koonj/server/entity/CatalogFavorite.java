package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataWriteRequest.Scope;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "catalog_favorites",
        uniqueConstraints = @UniqueConstraint(columnNames = {"account_subject", "connection_id", "scope", "path"}))
@Getter
@Setter
public class CatalogFavorite {
    @Id @GeneratedValue
    private UUID id;
    @Column(name = "account_subject", nullable = false)
    private String accountSubject;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private StorageConnection connection;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Scope scope;
    @Column(nullable = false, columnDefinition = "text")
    private String path;
    @Column(nullable = false)
    private Instant createdAt;
}
