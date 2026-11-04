package com.onrelearn.material.storage;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

// * Content-Length가 없는 응답도 수신 중 크기를 검사해 파일 전체를 무제한 적재하지 않는다.
final class LimitedPdfBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final long maxBytes;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    LimitedPdfBodySubscriber(long maxBytes, boolean accepted) {
        this.maxBytes = maxBytes;
        if (!accepted) body.completeExceptionally(new StorageUnavailableException());
    }

    @Override public CompletionStage<byte[]> getBody() { return body; }

    @Override public void onSubscribe(Flow.Subscription incoming) {
        subscription = incoming;
        if (body.isDone()) incoming.cancel();
        else incoming.request(1);
    }

    @Override public void onNext(List<ByteBuffer> chunks) {
        for (ByteBuffer chunk : chunks) {
            if (chunk.remaining() > maxBytes - bytes.size()) {
                // ! 한도 초과 시 수신을 취소하며 원격 본문·URL·키는 예외 메시지에 넣지 않는다.
                subscription.cancel();
                body.completeExceptionally(new StorageUnavailableException());
                return;
            }
            byte[] next = new byte[chunk.remaining()];
            chunk.get(next);
            bytes.writeBytes(next);
        }
        subscription.request(1);
    }

    @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }

    @Override public void onComplete() { body.complete(bytes.toByteArray()); }
}
