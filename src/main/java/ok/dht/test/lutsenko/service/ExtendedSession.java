package ok.dht.test.lutsenko.service;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.net.Socket;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExtendedSession extends HttpSession {

    public static ExecutorService executor = Executors.newFixedThreadPool(4,
            r -> new Thread(r, "ExtendedSessionThread"));

    public ExtendedSession(Socket socket, HttpServer server) {
        super(socket, server);
    }

    public static ExtendedSession of(HttpSession session) {
        return (ExtendedSession) session;
    }

    public void sendQueueItem(QueueItem queueItem) throws IOException {
        write(queueItem);
        super.server.incRequestsProcessed();
        this.handling = pipeline.pollFirst();
    }

    @Override
    protected void processWrite() throws Exception {
        if (eventsToListen == READABLE || eventsToListen == (SSL | WRITEABLE)) {
            throw new IOException("Illegal subscription state: " + eventsToListen);
        }
        executor.execute(() -> {
            try {
                for (QueueItem item = queueHead; item != null; queueHead = item = item.next()) {
                    int written = item.write(socket);
                    if (item.remaining() > 0) {
                        listen(written >= 0 ? WRITEABLE : SSL | READABLE);
                        return;
                    }
                    item.release();
                }
            } catch (IOException e) {
                ServiceUtils.closeSession(this);
            }
        });
        if (closing) {
            close();
        } else {
            listen(READABLE);
        }
    }
}
