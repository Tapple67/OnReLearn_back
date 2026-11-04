package com.onrelearn;

import com.onrelearn.material.storage.StorageUnavailableException;
import com.onrelearn.material.storage.SupabasePdfObjectStorage;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupabasePdfObjectStorageTests {
    @Test
    void signedUrlUsesOwnedBucketPathAndRejectsUnexpectedResponse() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var invalidResponse = new java.util.concurrent.atomic.AtomicBoolean(false);
        server.createContext("/storage/v1/object/sign/materials/users/owner/materials/document.pdf", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("{\"expiresIn\":600}",
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String payload = invalidResponse.get()
                ? "{\"signedURL\":\"https://outside.invalid/?token=bad\"}"
                : "{\"signedURL\":\"/object/sign/materials/users/owner/materials/document.pdf?token=synthetic\"}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            var storage = new SupabasePdfObjectStorage(origin, "synthetic-key", "materials", Duration.ofSeconds(3));
            assertEquals(origin + "/storage/v1/object/sign/materials/users/owner/materials/document.pdf?token=synthetic",
                storage.signedUrl("users/owner/materials/document.pdf", 600));
            invalidResponse.set(true);
            assertThrows(StorageUnavailableException.class,
                () -> storage.signedUrl("users/owner/materials/document.pdf", 600));
            assertThrows(IllegalArgumentException.class,
                () -> storage.signedUrl("users/owner/materials/document.pdf", 0));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void uploadAndCompensationDeleteKeepPathAndCredentialsAtStorageBoundary() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        var method = new AtomicReference<String>();
        var contentType = new AtomicReference<String>();
        var upsert = new AtomicReference<String>();
        server.createContext("/storage/v1/object/materials", exchange -> {
            calls.incrementAndGet();
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            upsert.set(exchange.getRequestHeaders().getFirst("x-upsert"));
            assertEquals("Bearer synthetic-key", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("synthetic-key", exchange.getRequestHeaders().getFirst("apikey"));
            if (exchange.getRequestMethod().equals("POST")) {
                assertEquals("/storage/v1/object/materials/users/owner/materials/document.pdf",
                    exchange.getRequestURI().getPath());
                assertArrayEquals("%PDF-1.7".getBytes(StandardCharsets.US_ASCII),
                    exchange.getRequestBody().readAllBytes());
            } else {
                assertEquals("/storage/v1/object/materials", exchange.getRequestURI().getPath());
                assertEquals("{\"prefixes\":[\"users/owner/materials/document.pdf\"]}",
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var storage = new SupabasePdfObjectStorage("http://127.0.0.1:" + server.getAddress().getPort(),
                "synthetic-key", "materials", Duration.ofSeconds(3));
            storage.upload("users/owner/materials/document.pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
            assertEquals("POST", method.get());
            assertEquals("application/pdf", contentType.get());
            assertEquals("false", upsert.get());
            storage.delete("users/owner/materials/document.pdf");
            assertEquals("DELETE", method.get());
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingConfigurationAndRemoteFailureStaySafe() throws Exception {
        var missing = new SupabasePdfObjectStorage("", "", "materials", Duration.ofSeconds(3));
        assertThrows(StorageUnavailableException.class,
            () -> missing.upload("users/owner/materials/document.pdf", new byte[0]));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var storage = new SupabasePdfObjectStorage("http://127.0.0.1:" + server.getAddress().getPort(),
                "synthetic-key", "materials", Duration.ofSeconds(3));
            var failure = assertThrows(StorageUnavailableException.class,
                () -> storage.upload("users/owner/materials/document.pdf", new byte[0]));
            assertFalse(failure.getMessage().contains("synthetic-key"));
            assertThrows(IllegalArgumentException.class,
                () -> storage.delete("../another-user/document.pdf"));
        } finally {
            server.stop(0);
        }
    }
}
