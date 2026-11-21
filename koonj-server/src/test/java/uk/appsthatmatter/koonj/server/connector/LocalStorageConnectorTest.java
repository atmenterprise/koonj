package uk.appsthatmatter.koonj.server.connector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.appsthatmatter.koonj.server.entity.EntryType;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class LocalStorageConnectorTest {
    @TempDir Path root;
    private final LocalStorageConnector connector = new LocalStorageConnector();

    @Test
    void scansBrokenAndDirectoryLinksWithoutFollowingThem() throws IOException {
        Files.createDirectories(root.resolve("actual"));
        Files.writeString(root.resolve("actual/file"), "data");
        Files.createSymbolicLink(root.resolve("shortcut"), Path.of("actual"));
        Files.createSymbolicLink(root.resolve("broken"), Path.of("missing"));

        assertThat(connector.listFiles(connection(), "")).extracting(ScannedFileInfo::relativePath)
                .containsExactlyInAnyOrder("actual", "actual/file", "shortcut", "broken");
        assertThat(connector.listFiles(connection(), "broken")).singleElement()
                .satisfies(link -> {
                    assertThat(link.entryType()).isEqualTo(EntryType.SYMLINK);
                    assertThat(link.symlinkTarget()).isEqualTo(root.toRealPath().resolve("missing").toString());
                });
        assertThatThrownBy(() -> connector.openReadStream(connection(), "shortcut/file"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void recognizesAndRestoresPhysicalFallbackStub() throws IOException {
        Path marker = root.resolve("file.koonj.symlink");
        Files.writeString(marker, "KOONJ-SYMLINK\ns3://archive/file");
        assertThat(connector.isSymlink(connection(), "file")).isTrue();
        assertThat(connector.listFiles(connection(), "")).singleElement()
                .satisfies(link -> {
                    assertThat(link.relativePath()).isEqualTo("file");
                    assertThat(link.symlinkTarget()).isEqualTo("s3://archive/file");
                });

        assertThatThrownBy(() -> connector.restore(connection(), "file", new ByteArrayInputStream(new byte[]{1}), 1,
                () -> { throw new IOException("commit failed"); })).hasMessage("commit failed");
        assertThat(marker).exists();
        assertThat(root.resolve("file")).doesNotExist();

        connector.writeStream(connection(), "file", new ByteArrayInputStream(new byte[]{1}), 1, null);
        assertThat(marker).doesNotExist();
        assertThat(Files.readAllBytes(root.resolve("file"))).containsExactly(1);
    }

    private StorageConnection connection() {
        var connection = new StorageConnection();
        connection.setBasePathOrBucket(root.toString());
        return connection;
    }
}
