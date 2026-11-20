package uk.appsthatmatter.koonj.server.connector;

import org.springframework.stereotype.Component;
import uk.appsthatmatter.koonj.server.entity.ProviderType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class StorageConnectorFactory {
    private final Map<ProviderType, StorageConnector> connectors = new EnumMap<>(ProviderType.class);

    public StorageConnectorFactory(List<StorageConnector> implementations) {
        for (ProviderType type : ProviderType.values()) {
            var matches = implementations.stream().filter(connector -> connector.supports(type)).toList();
            if (matches.size() != 1) throw new IllegalStateException("Expected one connector for " + type);
            connectors.put(type, matches.getFirst());
        }
    }

    public StorageConnector resolve(ProviderType type) {
        return connectors.get(type);
    }
}
