package com.malaysia.restaurant.config;

import com.malaysia.restaurant.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Configuration
public class SecurityConfig {
    private final List<String> allowedOrigins;
    private final AuthService authService;

    public SecurityConfig(AuthService authService,
                          @Value("${restaurant.security.allowed-origin-patterns:http://127.0.0.1:*,http://localhost:*,http://192.168.*:*,http://10.*:*,http://172.16.*:*,http://172.17.*:*,http://172.18.*:*,http://172.19.*:*,http://172.20.*:*,http://172.21.*:*,http://172.22.*:*,http://172.23.*:*,http://172.24.*:*,http://172.25.*:*,http://172.26.*:*,http://172.27.*:*,http://172.28.*:*,http://172.29.*:*,http://172.30.*:*,http://172.31.*:*}") String allowedOriginPatterns) {
        this.authService = authService;
        this.allowedOrigins = List.of(allowedOriginPatterns.split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Flutter Web/Vue dev servers run on different local ports, so API requests need CORS.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/common/auth/login", "/api/common/auth/captcha",
                                "/api/common/auth/sms-code", "/api/common/i18n/list",
                                "/api/common/events").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .addFilterBefore(new BearerTokenFilter(authService), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(allowedOrigins);
        // Keep the custom `lang` header, because all three clients pass language through requests.
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "lang", "X-Requested-With", "X-Idempotency-Key"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private static class BearerTokenFilter extends OncePerRequestFilter {
        private final AuthService authService;

        private BearerTokenFilter(AuthService authService) {
            this.authService = authService;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            if (isPublic(request) || HttpMethod.OPTIONS.matches(request.getMethod())) {
                filterChain.doFilter(request, response);
                return;
            }
            String path = request.getRequestURI();
            String token = request.getHeader("Authorization");
            if (path.startsWith("/api/") && (token == null || !token.startsWith("Bearer "))) {
                unauthorized(response, "请先登录");
                return;
            }
            if (token != null && token.startsWith("Bearer ")) {
                try {
                    AuthService.AuthContext context = authService.parse(token);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(context, null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (IllegalArgumentException ex) {
                    unauthorized(response, ex.getMessage());
                    return;
                }
            }
            filterChain.doFilter(request, response);
        }

        private boolean isPublic(HttpServletRequest request) {
            String path = request.getRequestURI();
            return path.equals("/api/common/auth/login")
                    || path.equals("/api/common/auth/captcha")
                    || path.equals("/api/common/auth/sms-code")
                    || path.equals("/api/common/i18n/list")
                    || path.equals("/api/common/events");
        }

        private void unauthorized(HttpServletResponse response, String message) throws IOException {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"" + message.replace("\"", "\\\"") + "\",\"data\":null}");
        }
    }
}
