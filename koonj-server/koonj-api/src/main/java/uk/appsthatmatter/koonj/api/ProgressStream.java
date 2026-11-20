package uk.appsthatmatter.koonj.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class ProgressStream implements AutoCloseable {
    private final ScanController scans;
    private final InventoryController operations;
    private final ObjectMapper json;
    private final Semaphore slots;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();

    ProgressStream(ScanController scans, InventoryController operations, ObjectMapper json,
                   @Value("${koonj.sse.subscribers:32}") int subscribers) {
        this.scans = scans;
        this.operations = operations;
        this.json = json;
        this.slots = new Semaphore(subscribers);
    }

    @GetMapping("/api/events/{kind}/{id}")
    void open(@PathVariable("kind") String kind, @PathVariable("id") long id,
              HttpServletRequest request, HttpServletResponse response) throws IOException {
        Supplier<Map<String, Object>> status = switch (kind) {
            case "scans" -> () -> scans.status(id);
            case "operations" -> () -> operations.status(id);
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        try {
            status.get();
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Accel-Buffering", "no");
            AsyncContext async = request.startAsync();
            async.setTimeout(0);
            var subscription = new Subscription(async, response.getOutputStream(), status);
            subscription.start();
        } catch (IOException | RuntimeException failure) {
            slots.release();
            throw failure;
        }
    }

    private final class Subscription implements AsyncListener, WriteListener {
        private final AsyncContext async;
        private final ServletOutputStream out;
        private final Supplier<Map<String, Object>> status;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean busy = new AtomicBoolean();
        private volatile ScheduledFuture<?> ticks;
        private volatile long lastWrite = System.nanoTime();
        private String previous;

        Subscription(AsyncContext async, ServletOutputStream out, Supplier<Map<String, Object>> status) {
            this.async = async;
            this.out = out;
            this.status = status;
        }

        void start() throws IOException {
            async.addListener(this);
            out.setWriteListener(this);
            ticks = timer.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
            if (closed.get()) ticks.cancel(false);
        }

        private void tick() {
            if (System.nanoTime() - lastWrite > TimeUnit.SECONDS.toNanos(15)) {
                finish();
            } else if (!closed.get() && busy.compareAndSet(false, true)) {
                writers.execute(this::write);
            }
        }

        private void write() {
            try {
                if (closed.get() || !out.isReady()) return;
                String current = json.writeValueAsString(status.get());
                String frame = current.equals(previous) ? ": heartbeat\n\n"
                        : "event: progress\ndata: " + current + "\n\n";
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
                previous = current;
                lastWrite = System.nanoTime();
            } catch (IOException | RuntimeException failure) {
                finish();
            } finally {
                busy.set(false);
            }
        }

        private void finish() {
            if (!closed.compareAndSet(false, true)) return;
            if (ticks != null) ticks.cancel(false);
            try {
                async.complete();
            } catch (IllegalStateException alreadyCompleted) {
                // Container already completed the async request.
            } finally {
                slots.release();
            }
        }

        @Override public void onWritePossible() { /* The next tick sends the latest persisted value. */ }
        @Override public void onError(Throwable error) { finish(); }
        @Override public void onComplete(AsyncEvent event) { finish(); }
        @Override public void onTimeout(AsyncEvent event) { finish(); }
        @Override public void onError(AsyncEvent event) { finish(); }
        @Override public void onStartAsync(AsyncEvent event) {}
    }

    @Override
    public void close() {
        timer.shutdownNow();
        writers.shutdownNow();
    }
}
