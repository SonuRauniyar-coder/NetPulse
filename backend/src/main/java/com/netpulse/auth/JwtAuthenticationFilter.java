package com.netpulse.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Reads "Authorization: Bearer <jwt>" and, if valid, authenticates the request. Invalid tokens -> anonymous -> 401. */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final SessionVerifier sessions;

    public JwtAuthenticationFilter(SessionVerifier sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            sessions.verify(header.substring(7)).ifPresent(p ->
                    SecurityContextHolder.getContext().setAuthentication(toAuthentication(p)));
        }
        chain.doFilter(req, res);
    }

    public static Authentication toAuthentication(JwtService.Principal p) {
        return new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name())));
    }
}
