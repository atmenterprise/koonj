package uk.appsthatmatter.koonj.server.connector;

import org.springframework.stereotype.Component;
import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

@Component
public class SmbStorageConnector extends LocalStorageConnector {
    @Override
    public boolean supports(ProviderType type) { return type == ProviderType.SMB; }

    public static void validateShare(String share) {
        if (networkShare(share) || Path.of(share).isAbsolute()) return;
        throw new IllegalArgumentException("SMB requires an absolute mount path or a Windows UNC share");
    }

    private static boolean networkShare(String share) {
        return share.startsWith("smb://") || share.startsWith("//") || share.startsWith("\\\\");
    }

    @Override
    protected Path configuredPath(StorageConnection config) throws IOException {
        String share = config.getBasePathOrBucket();
        if (!networkShare(share)) return Path.of(share);
        if (File.separatorChar != '\\') throw new IOException("Mount the SMB share on the server and register its absolute mount path");
        return Path.of((share.startsWith("smb://") ? share.substring(4) : share).replace('/', '\\'));
    }
}
