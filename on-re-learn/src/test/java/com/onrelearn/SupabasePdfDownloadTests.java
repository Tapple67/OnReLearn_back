package com.onrelearn;

import com.onrelearn.material.storage.StorageUnavailableException;
import com.onrelearn.material.storage.SupabasePdfObjectStorage;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupabasePdfDownloadTests {
    private static final String PATH = "users/owner/materials/document.pdf";
    private static final String ENDPOINT = "/storage/v1/object/authenticated/materials/" + PATH;
    private static final byte[] PDF = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);

    private SupabasePdfObjectStorage storage(HttpServer server, Duration timeout) {
        return new SupabasePdfObjectStorage("http://127.0.0.1:" + server.getAddress().getPort(),
            "synthetic-key", "materials", timeout);
    }

    @Test void downloadsExactBytesAndRejectsInvalidPathsBeforeRequest() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        var authenticated = new AtomicBoolean();
        server.createContext(ENDPOINT, exchange -> {
            calls.incrementAndGet();
            authenticated.set("GET".equals(exchange.getRequestMethod())
                && "Bearer synthetic-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                && "synthetic-key".equals(exchange.getRequestHeaders().getFirst("apikey")));
            exchange.sendResponseHeaders(200, PDF.length);
            exchange.getResponseBody().write(PDF);
            exchange.close();
        });
        server.start();
        try {
            var client = storage(server, Duration.ofSeconds(3));
            assertArrayEquals(PDF, client.download(PATH, PDF.length));
            assertTrue(authenticated.get());
            assertThrows(IllegalArgumentException.class, () -> client.download("../outside.pdf", 100));
            assertThrows(IllegalArgumentException.class, () -> client.download(PATH, 0));
            assertThrows(StorageUnavailableException.class, () ->
                new SupabasePdfObjectStorage("", "", "materials", Duration.ofSeconds(1)).download(PATH, 100));
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test void rejectsOversizeWithAndWithoutContentLength() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var chunked = new AtomicBoolean();
        server.createContext(ENDPOINT, exchange -> {
            try {
                exchange.sendResponseHeaders(200, chunked.get() ? 0 : PDF.length);
                exchange.getResponseBody().write(PDF);
            } catch (java.io.IOException cancelled) {
                // * 한도 초과 시 클라이언트가 수신 연결을 취소할 수 있다.
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var client = storage(server, Duration.ofSeconds(3));
            assertThrows(StorageUnavailableException.class, () -> client.download(PATH, PDF.length - 1));
            chunked.set(true);
            assertThrows(StorageUnavailableException.class, () -> client.download(PATH, PDF.length - 1));
            assertArrayEquals(PDF, client.download(PATH, PDF.length));
        } finally { server.stop(0); }
    }

    @Test void rejectsRemoteErrorsAndDoesNotFollowRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new AtomicInteger();
        var redirects = new AtomicInteger();
        server.createContext(ENDPOINT, exchange -> {
            exchange.getResponseHeaders().add("Location", "/redirect-target");
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.createContext("/redirect-target", exchange -> {
            redirects.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        try {
            var client = storage(server, Duration.ofSeconds(3));
            for (int code : new int[] {403, 404, 503, 302, 206}) {
                status.set(code);
                var error = assertThrows(StorageUnavailableException.class, () -> client.download(PATH, 100));
                assertFalse(error.getMessage().contains("synthetic-key"));
            }
            assertEquals(0, redirects.get());
        } finally { server.stop(0); }
    }

    @Test void deadlineCancelsStalledBodyAfterHeaders() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        var headersSent = new AtomicBoolean();
        server.createContext(ENDPOINT, exchange -> {
            try {
                exchange.sendResponseHeaders(200, 20);
                exchange.getResponseBody().write(PDF);
                exchange.getResponseBody().flush();
                headersSent.set(true);
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var client = storage(server, Duration.ofMillis(500));
            assertTimeout(Duration.ofSeconds(3), () ->
                assertThrows(StorageUnavailableException.class, () -> client.download(PATH, 100)));
            assertTrue(headersSent.get());
        } finally { release.countDown(); server.stop(0); }
    }
}
