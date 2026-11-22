package uk.appsthatmatter.koonj.fuse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@SpringBootApplication
public class KoonjFuseApplication {
    public static void main(String[] args) throws Exception {
        try (var application = SpringApplication.run(KoonjFuseApplication.class, args)) {
            Environment env = application.getEnvironment();
            var backing = Path.of(required(env, "koonj.fuse.backing-dir")).toRealPath();
            var mount = Path.of(required(env, "koonj.fuse.mount-point")).toAbsolutePath().normalize();
            boolean windows = File.separatorChar == '\\';
            if (!Files.isDirectory(backing)) throw new IllegalArgumentException("Backing path must be a directory");
            Path mountLocation = Files.exists(mount) ? mount.toRealPath() : mount;
            if (mountLocation.startsWith(backing) || backing.startsWith(mountLocation)) {
                throw new IllegalArgumentException("Backing and mount directories must not overlap");
            }
            if (!windows && !Files.isDirectory(mountLocation)) {
                throw new IllegalArgumentException("Create the mount directory before starting Koonj FUSE");
            }
            int timeout = env.getRequiredProperty("koonj.fuse.recall-timeout-seconds", Integer.class);
            if (timeout < 1 || timeout > 60) throw new IllegalArgumentException("Recall timeout must be 1–60 seconds");
            var agent = new KoonjAgentClient(required(env, "koonj.server-url"), required(env, "koonj.agent.token-uri"),
                    required(env, "koonj.agent.client-id"), required(env, "koonj.agent.client-secret"), timeout);

            var options = new ArrayList<String>();
            if (windows) {
                options.addAll(List.of("-o", "uid=-1,gid=-1,volname=Koonj,FileInfoTimeout=1000"));
            } else {
                options.addAll(List.of("-o", "fsname=koonj,default_permissions,attr_timeout=1,entry_timeout=1"));
                if (env.getProperty("koonj.fuse.allow-other", Boolean.class, false)) {
                    options.addAll(List.of("-o", "allow_other"));
                }
            }

            boolean recallOnRead = env.getProperty("koonj.fuse.recall-on-read", Boolean.class, false);
            try (var filesystem = new KoonjFuseFilesystem(backing, agent, recallOnRead)) {
                Runnable unmount = filesystem::umount;
                Thread shutdown = Thread.ofPlatform().name("koonj-unmount").unstarted(unmount);
                Runtime.getRuntime().addShutdownHook(shutdown);
                try {
                    filesystem.mount(mountLocation, true, false, options.toArray(String[]::new));
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(shutdown);
                    } catch (IllegalStateException shuttingDown) {
                        // The hook is already running.
                    }
                }
            }
        }
    }

    private static String required(Environment env, String name) {
        String value = env.getRequiredProperty(name);
        if (value.isBlank()) throw new IllegalArgumentException("Set " + name + " before starting Koonj FUSE");
        return value;
    }
}
