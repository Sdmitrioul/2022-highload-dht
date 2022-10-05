package ok.dht.test.mikhaylov;

import ok.dht.Service;
import ok.dht.ServiceConfig;
import ok.dht.test.ServiceFactory;
import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.net.Session;
import one.nio.server.AcceptorConfig;
import one.nio.server.SelectorThread;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MyService implements Service {

    private final ServiceConfig config;

    private HttpServer server;

    private RocksDB db;

    private ExecutorService requestHandlers;

    private static final byte[] EMPTY_ID_RESPONSE_BODY = strToBytes("Empty id");
    private static final int REQUEST_HANDLERS = 4;
    private static final int MAX_REQUESTS = 1024;

    public MyService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<?> start() throws IOException {
        requestHandlers = new ThreadPoolExecutor(
                REQUEST_HANDLERS,
                REQUEST_HANDLERS,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingDeque<>(MAX_REQUESTS)
        );
        try {
            db = RocksDB.open(config.workingDir().toString());
        } catch (RocksDBException e) {
            throw new IOException(e);
        }
        server = new MyHttpServer(createServerConfigFromPort(config.selfPort()));
        server.addRequestHandlers(this);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    private static HttpServerConfig createServerConfigFromPort(int port) {
        HttpServerConfig httpConfig = new HttpServerConfig();
        AcceptorConfig acceptor = new AcceptorConfig();
        acceptor.port = port;
        acceptor.reusePort = true;
        httpConfig.acceptors = new AcceptorConfig[]{acceptor};
        return httpConfig;
    }

    @Override
    public CompletableFuture<?> stop() throws IOException {
        server.stop();
        server = null;
        requestHandlers.shutdownNow();
        try {
            db.closeE();
        } catch (RocksDBException e) {
            throw new IOException(e);
        }
        db = null;
        return CompletableFuture.completedFuture(null);
    }

    private static byte[] strToBytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response handleGet(@Param(value = "id", required = true) final String id) {
        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, EMPTY_ID_RESPONSE_BODY);
        }
        try {
            byte[] value = db.get(strToBytes(id));
            if (value == null) {
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            } else {
                return new Response(Response.OK, value);
            }
        } catch (RocksDBException e) {
            return new Response(Response.INTERNAL_ERROR, strToBytes(e.getMessage()));
        }
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response handlePut(@Param(value = "id", required = true) final String id, final Request request) {
        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, EMPTY_ID_RESPONSE_BODY);
        }
        try {
            db.put(strToBytes(id), request.getBody());
            return new Response(Response.CREATED, Response.EMPTY);
        } catch (RocksDBException e) {
            return new Response(Response.INTERNAL_ERROR, strToBytes(e.getMessage()));
        }
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response handleDelete(@Param(value = "id", required = true) final String id) {
        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, EMPTY_ID_RESPONSE_BODY);
        }
        try {
            db.delete(strToBytes(id));
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } catch (RocksDBException e) {
            return new Response(Response.INTERNAL_ERROR, strToBytes(e.getMessage()));
        }
    }

    @ServiceFactory(stage = 2, week = 1, bonuses = "SingleNodeTest#respectFileFolder")
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new MyService(config);
        }
    }

    private class MyHttpServer extends HttpServer {
        public MyHttpServer(HttpServerConfig config) throws IOException {
            super(config);
        }

        @Override
        public void handleRequest(Request request, HttpSession session) throws IOException {
            try {
                requestHandlers.submit(() -> {
                    try {
                        super.handleRequest(request, session);
                    } catch (Exception e) {
                        handleRequestException(e, session);
                    }
                });
            } catch (RejectedExecutionException ignored) {
                session.sendError(Response.SERVICE_UNAVAILABLE, "Server is overloaded");
            }
        }

        public void handleRequestException(Exception e, HttpSession session) {
            try {
                // missing required parameter
                if (e instanceof HttpException && e.getCause() instanceof NoSuchElementException) {
                    session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                } else {
                    session.sendError(Response.INTERNAL_ERROR, e.getMessage());
                }
            } catch (IOException ex) {
                RuntimeException re = new RuntimeException(ex);
                re.addSuppressed(e);
                throw re;
            }
        }

        @Override
        public void handleDefault(Request request, HttpSession session) throws IOException {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
        }

        @Override
        public synchronized void stop() {
            for (SelectorThread selectorThread : selectors) {
                for (Session session : selectorThread.selector) {
                    session.close();
                }
            }
            super.stop();
        }
    }
}
