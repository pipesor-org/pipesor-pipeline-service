package org.pipesor.pipeline.shared.infrastructure.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Response body subscriber that cancels the exchange once the body exceeds a fixed byte budget. */
public final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int maxBytes;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    private BoundedBody(int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("Positive response limit required");
        this.maxBytes = maxBytes;
    }

    public static HttpResponse.BodyHandler<byte[]> handler(int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("Positive response limit required");
        return ignored -> new BoundedBody(maxBytes);
    }

    @Override public CompletionStage<byte[]> getBody() { return result; }

    @Override public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        result.whenComplete((unused, failure) -> { if (result.isCancelled()) value.cancel(); });
        value.request(1);
    }

    @Override public void onNext(List<ByteBuffer> buffers) {
        for (var buffer : buffers) {
            if (buffer.remaining() > maxBytes - bytes.size()) {
                subscription.cancel();
                result.completeExceptionally(new IllegalStateException("Response too large"));
                return;
            }
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }

    @Override public void onError(Throwable failure) { result.completeExceptionally(new IllegalStateException("Response unavailable", failure)); }

    @Override public void onComplete() { result.complete(bytes.toByteArray()); }
}
