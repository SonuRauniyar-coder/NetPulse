package com.netpulse.common.web;

import com.netpulse.auth.JwtService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** The authenticated user of the current request, if there is one (background jobs have none). */
public final class CurrentUser {
    private CurrentUser() { }

    public static Optional<JwtService.Principal> get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof JwtService.Principal p ? Optional.of(p) : Optional.empty();
    }

    public static String name() {
        return get().map(JwtService.Principal::username).orElse("system");
    }
}
