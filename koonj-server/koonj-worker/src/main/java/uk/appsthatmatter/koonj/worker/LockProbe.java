package uk.appsthatmatter.koonj.worker;

import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

public final class LockProbe {
    private LockProbe() {}

    public static void main(String[] args) throws Exception {
        try (var channel = FileChannel.open(Path.of(args[1]), StandardOpenOption.WRITE,
                StandardOpenOption.CREATE, LinkOption.NOFOLLOW_LINKS); var lock = channel.tryLock()) {
            if ("--probe-lock".equals(args[0])) {
                if (lock != null) throw new IllegalStateException("Another process could acquire the lock");
            } else {
                if (lock == null) throw new IllegalStateException("Probe lock is already held");
                System.out.println("Probe lock held for 60 seconds");
                TimeUnit.SECONDS.sleep(60);
            }
        }
    }
}
