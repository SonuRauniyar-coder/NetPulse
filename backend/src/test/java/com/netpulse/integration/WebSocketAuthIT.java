package com.netpulse.integration;

import com.netpulse.realtime.LivePublisher;
import com.netpulse.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketAuthIT extends IntegrationTestBase {
    @Autowired SimpMessagingTemplate template;
    WebSocketStompClient client;

    @BeforeEach void setUp() {
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach void tearDown() {
        client.stop();
    }

    /** Completes when the server rejects a frame or drops the session. */
    private static class ErrorCapturing extends StompSessionHandlerAdapter {
        final CompletableFuture<Throwable> error = new CompletableFuture<>();
        @Override public void handleTransportError(StompSession s, Throwable t) { error.complete(t); }
        @Override public void handleException(StompSession s, @Nullable StompCommand c, StompHeaders h, byte[] p, Throwable t) { error.complete(t); }
        @Override public void handleFrame(StompHeaders h, Object p) { error.complete(new IllegalStateException("ERROR frame")); }
    }

    private CompletableFuture<StompSession> connect(String token, StompSessionHandlerAdapter handler) {
        StompHeaders headers = new StompHeaders();
        if (token != null) headers.add("Authorization", token);
        return client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers, handler);
    }

    private void assertRejected(String token) {
        ErrorCapturing h = new ErrorCapturing();
        CompletableFuture<Boolean> connectFailed = connect(token, h).handle((s, t) -> t != null);
        assertThat(CompletableFuture.anyOf(connectFailed, h.error.thenApply(t -> true)))
                .succeedsWithin(10, TimeUnit.SECONDS).isEqualTo(true);
    }

    @Test void connectWithoutTokenIsRejected() {
        assertRejected(null);
    }

    @Test void connectWithBadTokenIsRejected() {
        assertRejected("Bearer garbage");
    }

    @Test void authenticatedClientReceivesLiveEvents() throws Exception {
        StompSession session = connect(tokenFor(Role.VIEWER), new ErrorCapturing()).get(10, TimeUnit.SECONDS);
        CompletableFuture<Object> received = new CompletableFuture<>();
        session.subscribe(LivePublisher.TOPIC_MONITORING, new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return Map.class; }
            @Override public void handleFrame(StompHeaders h, Object p) { received.complete(p); }
        });
        Thread.sleep(300);  // allow SUBSCRIBE to reach the broker
        template.convertAndSend(LivePublisher.TOPIC_MONITORING, Map.of("hello", "world"));
        assertThat(received.get(5, TimeUnit.SECONDS)).isEqualTo(Map.of("hello", "world"));
    }

    @Test void subscribingToUnknownTopicIsRejected() throws Exception {
        ErrorCapturing h = new ErrorCapturing();
        StompSession session = connect(tokenFor(Role.ADMIN), h).get(10, TimeUnit.SECONDS);
        session.subscribe("/topic/secret", new StompSessionHandlerAdapter() { });
        assertThat(h.error).succeedsWithin(5, TimeUnit.SECONDS);
    }

    @Test void socketClosesWhenTheAccountIsDeleted() throws Exception {
        String token = tokenFor(Role.VIEWER);
        ErrorCapturing h = new ErrorCapturing();
        connect(token, h).get(10, TimeUnit.SECONDS);
        users.deleteById(jwt.verify(token.substring(7)).orElseThrow().userId());
        assertThat(h.error).succeedsWithin(10, TimeUnit.SECONDS);
    }

    @Test void clientsCannotPublish() throws Exception {
        ErrorCapturing h = new ErrorCapturing();
        StompSession session = connect(tokenFor(Role.ADMIN), h).get(10, TimeUnit.SECONDS);
        session.send(LivePublisher.TOPIC_MONITORING, Map.of("forged", true));
        assertThat(h.error).succeedsWithin(5, TimeUnit.SECONDS);
    }
}
