package com.netpulse.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TopicAuthorizationTest {
    private static Authentication as(String role) {
        return new UsernamePasswordAuthenticationToken("u", null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static final String TRACE = "/topic/traceroute/" + UUID.randomUUID();

    @Test void everyRoleReadsSharedTopics() {
        assertThat(WebSocketAuthInterceptor.canSubscribe(as("VIEWER"), "/topic/monitoring")).isTrue();
        assertThat(WebSocketAuthInterceptor.canSubscribe(as("VIEWER"), "/topic/alerts")).isTrue();
    }

    @Test void onlyRolesThatCanRunTracesMayWatchThem() {
        assertThat(WebSocketAuthInterceptor.canSubscribe(as("ADMIN"), TRACE)).isTrue();
        assertThat(WebSocketAuthInterceptor.canSubscribe(as("OPERATOR"), TRACE)).isTrue();
        assertThat(WebSocketAuthInterceptor.canSubscribe(as("VIEWER"), TRACE)).isFalse();
    }

    @Test void wildcardsAndUnknownTopicsAreRefused() {
        assertThat(WebSocketAuthInterceptor.canSubscribe(as("ADMIN"), "/topic/traceroute/*")).isFalse();
        assertThat(WebSocketAuthInterceptor.canSubscribe(as("ADMIN"), "/topic/other")).isFalse();
        assertThat(WebSocketAuthInterceptor.canSubscribe(as("ADMIN"), null)).isFalse();
    }
}
