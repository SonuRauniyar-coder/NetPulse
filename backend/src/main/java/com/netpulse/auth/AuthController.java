package com.netpulse.auth;

import com.netpulse.auth.dto.AuthDtos.*;
import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.common.error.ApiException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public class AuthController {
    public static final String REFRESH_COOKIE = "np_refresh";
    public static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService auth;
    private final LoginRateLimiter rateLimiter;
    private final NetPulseProperties.Security cfg;

    public AuthController(AuthService auth, LoginRateLimiter rateLimiter, NetPulseProperties props) {
        this.auth = auth;
        this.rateLimiter = rateLimiter;
        this.cfg = props.security();
    }

    /** SPA calls this first: Spring writes the XSRF-TOKEN cookie, the SPA echoes it in X-XSRF-TOKEN. */
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest req) {
        if (!rateLimiter.tryAcquire(req.getRemoteAddr())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                    "Too many login attempts. Wait a minute and try again.");
        }
        AuthService.AuthResult r = auth.login(body.username(), body.password(), req.getRemoteAddr(), req.getHeader("User-Agent"));
        return withRefreshCookie(r);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String raw,
                                                 HttpServletRequest req) {
        return withRefreshCookie(auth.refresh(raw, req.getRemoteAddr(), req.getHeader("User-Agent")));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String raw,
                                       HttpServletResponse res) {
        auth.logout(raw);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @GetMapping("/me")
    public UserInfo me(@AuthenticationPrincipal JwtService.Principal p) {
        return new UserInfo(p.userId(), p.username(), p.role());
    }

    private ResponseEntity<TokenResponse> withRefreshCookie(AuthService.AuthResult r) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(r.rawRefreshToken(), cfg.refreshTokenTtl()).toString())
                .body(new TokenResponse(r.access().token(), r.access().expiresAt(), r.user()));
    }

    /**
     * HttpOnly: JavaScript can't read it (XSS can't steal it). Secure: HTTPS only in prod.
     * SameSite=Strict: browser never attaches it to cross-site requests. Path-scoped to /api/v1/auth so it is
     * not sent with every API call.
     */
    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true).secure(cfg.cookieSecure()).sameSite("Strict")
                .path(REFRESH_COOKIE_PATH).maxAge(maxAge).build();
    }
}
