package com.netpulse.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netpulse.auth.JwtAuthenticationFilter;
import com.netpulse.auth.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Security model:
 * <ul>
 *   <li>API calls authenticate with a short-lived JWT in the Authorization header. Browsers never attach that
 *       header automatically, so those endpoints are NOT exposed to CSRF.</li>
 *   <li>The refresh token lives in an HttpOnly cookie that browsers DO attach automatically. The endpoints that
 *       read it (login/refresh/logout) are CSRF-protected with the double-submit cookie pattern:
 *       the XSRF-TOKEN cookie value must be echoed in the X-XSRF-TOKEN header, which a cross-site attacker
 *       cannot do because they cannot read our cookies.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Set<String> CSRF_PROTECTED = Set.of(
            "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout");

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, NetPulseProperties props,
                                           ObjectMapper mapper) throws Exception {
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookiePath("/");
        csrfRepo.setCookieCustomizer(c -> c.secure(props.security().cookieSecure()).sameSite("Strict"));
        RequestMatcher csrfMatcher = req -> HttpMethod.POST.matches(req.getMethod())
                && CSRF_PROTECTED.contains(req.getRequestURI());

        http
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(c -> c.csrfTokenRepository(csrfRepo)
                    // plain (non-XOR) handler: the SPA sends back the raw cookie value
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .requireCsrfProtectionMatcher(csrfMatcher))
            .cors(c -> c.configurationSource(corsSource(props)))
            .headers(h -> h
                    .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    .frameOptions(f -> f.deny()))
            .authorizeHttpRequests(a -> a
                    .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/system/info").permitAll()
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                    // Not proxied by Nginx: only reachable on the internal Docker network for Prometheus scraping.
                    .requestMatchers("/actuator/prometheus").permitAll()
                    .requestMatchers("/actuator/**").hasRole("ADMIN")
                    .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll() // disabled in prod
                    .requestMatchers("/ws", "/ws/**").permitAll()   // authenticated at STOMP CONNECT, see WebSocketAuthInterceptor
                    .requestMatchers("/error").permitAll()
                    .anyRequest().authenticated())
            .exceptionHandling(e -> e
                    .authenticationEntryPoint((req, res, ex) ->
                            writeProblem(res, mapper, HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication is required."))
                    .accessDeniedHandler((req, res, ex) -> writeProblem(res, mapper, HttpStatus.FORBIDDEN, "Forbidden",
                            ex instanceof CsrfException ? "Missing or invalid CSRF token." : "You do not have permission for this action.")))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static CorsConfigurationSource corsSource(NetPulseProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.security().allowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN", "X-Request-Id"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", cfg);
        return src;
    }

    private static void writeProblem(HttpServletResponse res, ObjectMapper mapper, HttpStatus status,
                                     String title, String detail) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), pd);
    }
}
