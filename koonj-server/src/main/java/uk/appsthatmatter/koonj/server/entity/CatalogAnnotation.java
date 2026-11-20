package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataValues;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataWriteRequest.Scope;

import java.util.UUID;

@Entity
@Table(name = "catalog_annotations",
        uniqueConstraints = @UniqueConstraint(columnNames = {"connection_id", "scope", "path"}))
@Getter
@Setter
public class CatalogAnnotation {
    @Id @GeneratedValue
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private StorageConnection connection;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Scope scope;
    @Column(nullable = false, columnDefinition = "text")
    private String path;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private CatalogMetadataValues attributes;
}
