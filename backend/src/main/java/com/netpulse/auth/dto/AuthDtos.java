package com.netpulse.auth.dto;

import com.netpulse.user.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() { }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String password) { }

    public record UserInfo(UUID id, String username, Role role) { }

    /** The refresh token is deliberately NOT here - it only travels in an HttpOnly cookie. */
    public record TokenResponse(String accessToken, Instant expiresAt, UserInfo user) { }

    public record CsrfResponse(String headerName, String token) { }
}
