package com.netpulse.realtime;

import com.netpulse.auth.JwtAuthenticationFilter;
import com.netpulse.auth.JwtService;
import com.netpulse.auth.SessionVerifier;
import com.netpulse.traceroute.TracerouteService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Browsers cannot set an Authorization header on the WebSocket handshake, so authentication happens one level
 * up: the STOMP CONNECT frame must carry a valid access token. Subscriptions are then checked against an
 * explicit topic allowlist, and clients are never allowed to publish.
 */
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {
    /** Readable by every signed-in role. */
    static final Set<String> READABLE_TOPICS = Set.of(LivePublisher.TOPIC_MONITORING, LivePublisher.TOPIC_ALERTS);
    /** A trace can only be started by these roles, so only they may watch one. */
    private static final Pattern TRACE_TOPIC = Pattern.compile(Pattern.quote(TracerouteService.TOPIC_PREFIX) + "[0-9a-f-]{36}");
    private static final Set<String> TRACE_ROLES = Set.of("ROLE_ADMIN", "ROLE_OPERATOR");

    private final SessionVerifier sessions;
    private final LiveSessions live;

    public WebSocketAuthInterceptor(SessionVerifier sessions, LiveSessions live) {
        this.sessions = sessions;
        this.live = live;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;
        StompCommand command = accessor.getCommand();

        if (command == StompCommand.CONNECT) {
            String header = accessor.getFirstNativeHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) throw new MessageDeliveryException("Missing access token");
            JwtService.Principal p = sessions.verify(header.substring(7))
                    .orElseThrow(() -> new MessageDeliveryException("Invalid or expired access token"));
            accessor.setUser(JwtAuthenticationFilter.toAuthentication(p));
            live.authenticated(accessor.getSessionId(), p);
        } else if (command == StompCommand.SUBSCRIBE) {
            Principal user = accessor.getUser();
            if (user == null) throw new MessageDeliveryException("Not authenticated");
            if (!canSubscribe(user, accessor.getDestination())) {
                throw new MessageDeliveryException("Subscription to this destination is not allowed");
            }
        } else if (command == StompCommand.SEND) {
            throw new MessageDeliveryException("Clients may not publish");
        }
        return message;
    }

    static boolean canSubscribe(Principal user, String destination) {
        if (destination == null) return false;
        if (READABLE_TOPICS.contains(destination)) return true;
        return TRACE_TOPIC.matcher(destination).matches() && user instanceof Authentication a
                && a.getAuthorities().stream().anyMatch(g -> TRACE_ROLES.contains(g.getAuthority()));
    }
}
