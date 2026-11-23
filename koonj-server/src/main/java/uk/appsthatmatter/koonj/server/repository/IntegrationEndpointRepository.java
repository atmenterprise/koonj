package uk.appsthatmatter.koonj.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.appsthatmatter.koonj.server.entity.IntegrationEndpoint;
import java.util.List;
import java.util.UUID;

public interface IntegrationEndpointRepository extends JpaRepository<IntegrationEndpoint, UUID> {
    List<IntegrationEndpoint> findByProjectIdAndKindOrderByNameAscIdAsc(UUID projectId, IntegrationEndpoint.Kind kind);
}
