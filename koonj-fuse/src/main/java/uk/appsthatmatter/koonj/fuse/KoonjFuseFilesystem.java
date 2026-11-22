package uk.appsthatmatter.koonj.fuse;

import com.kenai.jffi.MemoryIO;
import jnr.constants.platform.OpenFlags;
import jnr.ffi.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientResponseException;
import ru.serce.jnrfuse.ErrorCodes;
import ru.serce.jnrfuse.FuseFillDir;
import ru.serce.jnrfuse.FuseStubFS;
import ru.serce.jnrfuse.struct.FileStat;
import ru.serce.jnrfuse.struct.FuseFileInfo;
import ru.serce.jnrfuse.struct.Statvfs;
import ru.serce.jnrfuse.struct.Timespec;

import java.io.File;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

final class KoonjFuseFilesystem extends FuseStubFS implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KoonjFuseFilesystem.class);
    private static final Pattern REFERENCE = Pattern.compile(
            "(?:koonj-ref:/+|\\.koonj-ref-)([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})");
    private static final boolean WINDOWS = File.separatorChar == '\\';

    private final Path backing;
    private final KoonjAgentClient agent;
    private final boolean recallOnRead;
    private final ConcurrentHashMap<UUID, CompletableFuture<Path>> inFlightRecalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, OpenFile> handles = new ConcurrentHashMap<>();
    private final AtomicLong nextHandle = new AtomicLong();
    private final java.util.concurrent.ExecutorService recalls = Executors.newVirtualThreadPerTaskExecutor();
    private final long uid;
    private final long gid;

    KoonjFuseFilesystem(Path backing, KoonjAgentClient agent, boolean recallOnRead) throws IOException {
        this.backing = backing;
        this.agent = agent;
        this.recallOnRead = recallOnRead;
        var owner = WINDOWS ? java.util.Map.<String, Object>of() : Files.readAttributes(backing, "unix:uid,gid");
        uid = ((Number) owner.getOrDefault("uid", -1)).longValue();
        gid = ((Number) owner.getOrDefault("gid", -1)).longValue();
    }

    @Override
    public int getattr(String path, FileStat stat) {
        try {
            Path entry = backingPath(path);
            BasicFileAttributes actual = attributes(entry);
            UUID id = archiveId(entry, actual);
            if (id == null && !actual.isDirectory() && !actual.isRegularFile()) return -ErrorCodes.ENOENT();
            long size = actual.size();
            Instant modified = actual.lastModifiedTime().toInstant();
            if (id != null) {
                var archived = metadata(entry, id);
                size = archived.sizeBytes();
                if (archived.lastModifiedAt() != null) modified = archived.lastModifiedAt();
            }

            stat.st_uid.set(uid);
            stat.st_gid.set(gid);
            stat.st_mode.set(actual.isDirectory() ? FileStat.S_IFDIR | 0755 : FileStat.S_IFREG | 0644);
            stat.st_nlink.set(actual.isDirectory() ? 2 : 1);
            stat.st_size.set(size);
            stat.st_blocks.set(id == null ? Math.ceilDiv(size, 512) : 0);
            time(stat.st_mtim, modified);
            time(stat.st_atim, id == null ? actual.lastAccessTime().toInstant() : modified);
            time(stat.st_ctim, modified);
            if (stat.st_birthtime != null) time(stat.st_birthtime, id == null ? actual.creationTime().toInstant() : modified);
            return 0;
        } catch (IOException | RuntimeException failure) {
            return errno(path, failure);
        }
    }

    @Override
    public int readdir(String path, Pointer buffer, FuseFillDir fill, long offset, FuseFileInfo info) {
        try {
            Path directory = backingPath(path);
            if (!attributes(directory).isDirectory()) return -ErrorCodes.ENOTDIR();
            if (!directory.toRealPath().equals(directory)) return -ErrorCodes.EACCES();
            try (var children = Files.newDirectoryStream(directory)) {
                if (fill.apply(buffer, ".", null, 0) != 0 || fill.apply(buffer, "..", null, 0) != 0) return 0;
                for (Path child : children) {
                    String name = child.getFileName().toString();
                    if (hidden(name)) continue;
                    try {
                        var actual = attributes(child);
                        if ((actual.isDirectory() || actual.isRegularFile() || archiveId(child, actual) != null)
                                && fill.apply(buffer, name, null, 0) != 0) break;
                    } catch (NoSuchFileException removedDuringListing) {
                        // A backing-directory update may remove entries while it is enumerated.
                    }
                }
                return 0;
            }
        } catch (IOException | RuntimeException failure) {
            return errno(path, failure);
        }
    }

    @Override
    public int open(String path, FuseFileInfo info) {
        long flags = info.flags.get();
        boolean writable = (flags & 3) == OpenFlags.O_RDWR.intValue();
        if (!writable && (flags & 3) != OpenFlags.O_RDONLY.intValue()) return -ErrorCodes.EACCES();
        if ((flags & (OpenFlags.O_TRUNC.intValue() | OpenFlags.O_APPEND.intValue())) != 0) return -ErrorCodes.EACCES();

        try {
            Path entry = backingPath(path);
            BasicFileAttributes actual = attributes(entry);
            UUID id = archiveId(entry, actual);
            if (id != null) {
                metadata(entry, id);
            } else if (!actual.isRegularFile()) {
                return actual.isDirectory() ? -ErrorCodes.EISDIR() : -ErrorCodes.ENOENT();
            }

            var file = new OpenFile(entry, id, writable);
            // Samba can open a handle for a metadata query without reading content.
            if (id == null || !recallOnRead) file.channel();
            long handle = nextHandle.incrementAndGet();
            handles.put(handle, file);
            info.fh.set(handle);
            return 0;
        } catch (IOException | InterruptedException | ExecutionException | RuntimeException failure) {
            return errno(path, failure);
        }
    }

    private Path awaitRecall(UUID id, Path entry) throws InterruptedException, ExecutionException {
        CompletableFuture<Path> recall = inFlightRecalls.computeIfAbsent(id, fileId ->
                CompletableFuture.supplyAsync(() -> restore(fileId, entry), recalls));
        recall.whenComplete((result, failure) -> inFlightRecalls.remove(id, recall));
        return recall.get();
    }

    private Path restore(UUID id, Path entry) {
        var result = agent.recall(id);
        if ("RESTORING_GLACIER".equals(result.status())) {
            log.info("S3 is restoring {} ({}). Try opening again after {}.", entry, id, result.estimatedReadyAt());
            throw new RecallPending();
        }
        if (!"RECALLED".equals(result.status())) throw new IllegalStateException("Unexpected recall status");
        if (!Files.isRegularFile(entry, NOFOLLOW_LINKS)) {
            log.warn("Recall completed for {} but {} is still offline. Check that the server and mount share the backing store.",
                    id, entry);
            throw new RecallPending();
        }
        return entry;
    }

    @Override
    public int read(String path, Pointer buffer, long size, long offset, FuseFileInfo info) {
        var file = handles.get(info.fh.get());
        if (file == null) return -ErrorCodes.EBADF();
        if (size == 0) return 0;
        try {
            FileChannel channel = file.channel();
            var bytes = MemoryIO.getInstance().newDirectByteBuffer(buffer.address(), Math.toIntExact(size));
            while (bytes.hasRemaining()) {
                if (channel.read(bytes, offset + bytes.position()) <= 0) break;
            }
            return bytes.position();
        } catch (IOException | InterruptedException | ExecutionException | RuntimeException failure) {
            return errno(path, failure);
        }
    }

    @Override
    public int write(String path, Pointer buffer, long size, long offset, FuseFileInfo info) {
        var file = handles.get(info.fh.get());
        if (file == null || !file.writable) return -ErrorCodes.EBADF();
        if (size == 0) return 0;
        try {
            FileChannel channel = file.channel();
            var bytes = MemoryIO.getInstance().newDirectByteBuffer(buffer.address(), Math.toIntExact(size));
            while (bytes.hasRemaining()) {
                if (channel.write(bytes, offset + bytes.position()) == 0) return -ErrorCodes.EIO();
            }
            return bytes.position();
        } catch (IOException | InterruptedException | ExecutionException | RuntimeException failure) {
            return errno(path, failure);
        }
    }

    @Override
    public int fsync(String path, int dataOnly, FuseFileInfo info) {
        var file = handles.get(info.fh.get());
        if (file == null) return -ErrorCodes.EBADF();
        try {
            file.force(dataOnly == 0);
            return 0;
        } catch (IOException | RuntimeException failure) {
            return errno(path, failure);
        }
    }

    @Override
    public int release(String path, FuseFileInfo info) {
        var file = handles.remove(info.fh.get());
        try {
            if (file != null) file.close();
            return 0;
        } catch (IOException failure) {
            return errno(path, failure);
        }
    }

    @Override
    public int statfs(String path, Statvfs stat) {
        try {
            var store = Files.getFileStore(backing);
            long unit = store.getBlockSize();
            stat.f_bsize.set(unit);
            stat.f_frsize.set(unit);
            stat.f_blocks.set(store.getTotalSpace() / unit);
            stat.f_bfree.set(store.getUnallocatedSpace() / unit);
            stat.f_bavail.set(store.getUsableSpace() / unit);
            stat.f_namemax.set(255);
            return 0;
        } catch (IOException | RuntimeException failure) {
            return errno(path, failure);
        }
    }

    private Path backingPath(String path) throws IOException {
        if (!path.startsWith("/")) throw new NoSuchFileException(path);
        Path relative = backing.getFileSystem().getPath(path.substring(1));
        if (relative.isAbsolute() || relative.getRoot() != null) throw new AccessDeniedException(path);
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("..") || name.equals(".") || hidden(name)) throw new NoSuchFileException(path);
        }
        Path resolved = backing.resolve(relative);
        if (!resolved.equals(backing) && !resolved.getParent().toRealPath().equals(resolved.getParent())) {
            throw new AccessDeniedException(path);
        }
        return resolved;
    }

    private static boolean hidden(String name) {
        return name.startsWith(".koonj-transfer-") && name.endsWith(".koonj.tmp");
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
    }

    private static UUID archiveId(Path path, BasicFileAttributes attributes) throws IOException {
        if (!attributes.isSymbolicLink()) return null;
        var match = REFERENCE.matcher(Files.readSymbolicLink(path).toString());
        return match.matches() ? UUID.fromString(match.group(1)) : null;
    }

    private KoonjAgentClient.Metadata metadata(Path path, UUID id) throws IOException {
        var record = agent.metadata(id);
        String relative = backing.relativize(path).toString().replace(File.separatorChar, '/');
        if (!id.equals(record.id()) || !("koonj-ref://" + id).equals(record.symlinkTarget())
                || !relative.equals(record.relativePath())) {
            throw new NoSuchFileException(path.toString());
        }
        return record;
    }

    private static void time(Timespec time, Instant instant) {
        time.tv_sec.set(instant.getEpochSecond());
        time.tv_nsec.set(instant.getNano());
    }

    private static int errno(String path, Throwable failure) {
        if (failure instanceof ExecutionException) return errno(path, failure.getCause());
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return -ErrorCodes.EINTR();
        }
        if (failure instanceof ClosedChannelException) return -ErrorCodes.EBADF();
        if (failure instanceof NoSuchFileException) return -ErrorCodes.ENOENT();
        if (failure instanceof NotDirectoryException) return -ErrorCodes.ENOTDIR();
        if (failure instanceof AccessDeniedException) return -ErrorCodes.EACCES();
        if (failure instanceof RecallPending) return -ErrorCodes.EAGAIN();
        if (failure instanceof RestClientResponseException response) {
            if (response.getStatusCode().value() == 504) return -ErrorCodes.EAGAIN();
            if (response.getStatusCode().value() == 404) return -ErrorCodes.ENOENT();
        }
        log.warn("Filesystem operation failed for {}", path, failure);
        return -ErrorCodes.EIO();
    }

    private static final class RecallPending extends RuntimeException {}

    @Override
    public synchronized void umount() {
        super.umount();
    }

    @Override
    public void mount(Path path, boolean blocking, boolean debug, String[] options) {
        try {
            super.mount(path, blocking, debug, options);
        } finally {
            if (blocking) mounted.set(false);
        }
    }

    @Override
    public void close() {
        recalls.shutdownNow();
        handles.forEach((id, handle) -> releaseHandle(handle));
        handles.clear();
    }

    private void releaseHandle(OpenFile handle) {
        try {
            handle.close();
        } catch (IOException failure) {
            log.warn("Could not close file handle during shutdown", failure);
        }
    }

    private final class OpenFile implements AutoCloseable {
        private final Path path;
        private final UUID archiveId;
        private final boolean writable;
        private FileChannel channel;
        private boolean closed;

        private OpenFile(Path path, UUID archiveId, boolean writable) {
            this.path = path;
            this.archiveId = archiveId;
            this.writable = writable;
        }

        private synchronized FileChannel channel() throws IOException, InterruptedException, ExecutionException {
            if (closed) throw new ClosedChannelException();
            if (channel == null) {
                Path local = archiveId != null && !Files.isRegularFile(path, NOFOLLOW_LINKS)
                        ? awaitRecall(archiveId, path) : path;
                channel = writable ? FileChannel.open(local, READ, WRITE, NOFOLLOW_LINKS)
                        : FileChannel.open(local, READ, NOFOLLOW_LINKS);
            }
            return channel;
        }

        private synchronized void force(boolean metadata) throws IOException {
            if (channel != null) channel.force(metadata);
        }

        @Override
        public synchronized void close() throws IOException {
            closed = true;
            if (channel != null) channel.close();
        }
    }

}
