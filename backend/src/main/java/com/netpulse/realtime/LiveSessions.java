package com.netpulse.realtime;

import com.netpulse.auth.JwtService;
import com.netpulse.auth.SessionVerifier;
import com.netpulse.user.AppUser;
import com.netpulse.user.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A socket is authenticated once, at CONNECT, but stays open indefinitely. This re-checks every open socket so one
 * ends when its token expires or its account loses access. The browser client reconnects with a fresh token.
 */
@Component
public class LiveSessions implements WebSocketHandlerDecoratorFactory {
    private static final Logger log = LoggerFactory.getLogger(LiveSessions.class);
    private static final CloseStatus SESSION_ENDED = new CloseStatus(4401, "Session ended");

    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final Map<String, JwtService.Principal> owners = new ConcurrentHashMap<>();
    private final SessionVerifier verifier;
    private final AppUserRepository users;

    public LiveSessions(SessionVerifier verifier, AppUserRepository users) {
        this.verifier = verifier;
        this.users = users;
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sockets.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sockets.remove(session.getId());
                owners.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    void authenticated(String sessionId, JwtService.Principal owner) {
        owners.put(sessionId, owner);
    }

    @Scheduled(fixedDelay = 5000)
    public void closeEndedSessions() {
        if (owners.isEmpty()) return;
        var ids = owners.values().stream().map(JwtService.Principal::userId).collect(Collectors.toSet());
        Map<UUID, AppUser> accounts = users.findAllById(ids).stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
        owners.forEach((sessionId, owner) -> {
            if (!verifier.allows(owner, accounts.get(owner.userId()))) close(sessionId);
        });
    }

    private void close(String sessionId) {
        WebSocketSession socket = sockets.get(sessionId);
        if (socket == null) return;
        try {
            socket.close(SESSION_ENDED);
        } catch (IOException e) {
            log.debug("Could not close WebSocket {}: {}", sessionId, e.getMessage());
        }
    }
}
