package com.onrelearn.material.storage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SupabasePdfObjectStorage implements PdfObjectStorage {
    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String serviceKey;
    private final String bucket;
    private final Duration timeout;

    public SupabasePdfObjectStorage(
        @Value("${onrelearn.storage.supabase.url:}") String baseUrl,
        @Value("${onrelearn.storage.supabase.service-key:}") String serviceKey,
        @Value("${onrelearn.storage.supabase.bucket:materials}") String bucket,
        @Value("${onrelearn.storage.supabase.timeout:PT10S}") Duration timeout) {
        this.baseUrl = baseUrl;
        this.serviceKey = serviceKey;
        this.bucket = bucket;
        this.timeout = timeout;
    }

    @Override
    public void upload(String path, byte[] pdf) {
        // ! 고유 경로에만 새 객체를 만들고 덮어쓰기를 금지해 기존 자료를 보존한다.
        var request = authorized(path).header("Content-Type", "application/pdf")
            .header("x-upsert", "false")
            .POST(HttpRequest.BodyPublishers.ofByteArray(pdf)).build();
        send(request);
    }

    @Override
    public void delete(String path) {
        // * 업로드 보상 및 소유자가 요청한 자료 삭제에서 지정한 객체만 제거한다.
        String body = "{\"prefixes\":[\"" + path + "\"]}";
        send(authorized(path, false).header("Content-Type", "application/json")
            .method("DELETE", HttpRequest.BodyPublishers.ofString(body)).build());
    }

    @Override
    public byte[] download(String path, long maxBytes) {
        if (maxBytes < 1 || maxBytes > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException("Invalid PDF download size limit");
        }
        // * worker는 서명 URL 대신 서버 인증 헤더로 지정 객체만 다운로드한다.
        var request = authorized(path, "authenticated/").GET().build();
        var response = http.sendAsync(request, info -> new LimitedPdfBodySubscriber(maxBytes,
            info.statusCode() == 200 && info.headers().firstValueAsLong("Content-Length").orElse(0) <= maxBytes));
        try {
            // ! 헤더 수신 후 본문이 멈춘 경우도 전체 다운로드 제한 시간으로 취소한다.
            return response.get(timeout.toNanos(), TimeUnit.NANOSECONDS).body();
        } catch (InterruptedException exception) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw new StorageUnavailableException(exception);
        } catch (ExecutionException | TimeoutException exception) {
            response.cancel(true);
            throw new StorageUnavailableException(exception);
        }
    }

    @Override
    public String signedUrl(String path, long ttlSeconds) {
        if (ttlSeconds < 1 || ttlSeconds > 86400) throw new IllegalArgumentException("Invalid signed URL TTL");
        var request = authorized(path, "sign/").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"expiresIn\":" + ttlSeconds + "}")).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StorageUnavailableException();
            }
            String signedPath = JsonMapper.builder().build().readTree(response.body())
                .path("signedURL").stringValue();
            // ! 외부 JSON은 신뢰하지 않는다. 서명 경로가 버킷 내 상대 경로인지 확인한다.
            if (signedPath == null || !signedPath.startsWith("/object/sign/" + bucket + "/" + path + "?token=")) {
                throw new StorageUnavailableException();
            }
            return baseUrl.replaceAll("/+$", "") + "/storage/v1" + signedPath;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new StorageUnavailableException(exception);
        } catch (RuntimeException exception) {
            if (exception instanceof StorageUnavailableException) throw exception;
            throw new StorageUnavailableException(exception);
        }
    }

    private HttpRequest.Builder authorized(String path) {
        return authorized(path, "");
    }

    private HttpRequest.Builder authorized(String path, boolean includePath) {
        return authorized(path, includePath ? "" : null);
    }

    private HttpRequest.Builder authorized(String path, String prefix) {
        if (baseUrl == null || baseUrl.isBlank() || serviceKey == null || serviceKey.isBlank()) {
            throw new StorageUnavailableException();
        }
        if (bucket == null || !bucket.matches("[A-Za-z0-9_-]+")
            || path == null || !path.matches("users/[A-Za-z0-9-]+/materials/[A-Za-z0-9-]+\\.pdf")) {
            throw new IllegalArgumentException("Invalid PDF storage path");
        }
        URI uri = URI.create(baseUrl.replaceAll("/+$", "") + "/storage/v1/object/"
            + (prefix == null ? bucket : prefix + bucket + "/" + path));
        return HttpRequest.newBuilder(uri).timeout(timeout)
            .header("Authorization", "Bearer " + serviceKey).header("apikey", serviceKey);
    }

    private void send(HttpRequest request) {
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // ! 외부 응답 본문·URL·키는 클라이언트 응답이나 로그에 포함하지 않는다.
                throw new StorageUnavailableException();
            }
        } catch (IOException exception) {
            throw new StorageUnavailableException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new StorageUnavailableException(exception);
        }
    }
}
