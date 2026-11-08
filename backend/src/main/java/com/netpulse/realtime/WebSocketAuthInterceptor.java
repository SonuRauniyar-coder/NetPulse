package com.netpulse.realtime;

import com.netpulse.auth.JwtAuthenticationFilter;
import com.netpulse.auth.JwtService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Set;

/**
 * Browsers cannot set an Authorization header on the WebSocket handshake, so authentication happens one level
 * up: the STOMP CONNECT frame must carry a valid access token. Subscriptions are then checked against an
 * explicit topic allowlist, and clients are never allowed to publish.
 */
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {
    /** Phase 1 topics are readable by every authenticated role; Phase 3 can add role-restricted ones here. */
    static final Set<String> READABLE_TOPICS = Set.of(LivePublisher.TOPIC_MONITORING);

    private final JwtService jwt;

    public WebSocketAuthInterceptor(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;
        StompCommand command = accessor.getCommand();

        if (command == StompCommand.CONNECT) {
            String header = accessor.getFirstNativeHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) throw new MessageDeliveryException("Missing access token");
            JwtService.Principal p = jwt.verify(header.substring(7))
                    .orElseThrow(() -> new MessageDeliveryException("Invalid or expired access token"));
            accessor.setUser(JwtAuthenticationFilter.toAuthentication(p));
        } else if (command == StompCommand.SUBSCRIBE) {
            Principal user = accessor.getUser();
            if (user == null) throw new MessageDeliveryException("Not authenticated");
            if (!READABLE_TOPICS.contains(accessor.getDestination())) {
                throw new MessageDeliveryException("Subscription to this destination is not allowed");
            }
        } else if (command == StompCommand.SEND) {
            throw new MessageDeliveryException("Clients may not publish");
        }
        return message;
    }
}
