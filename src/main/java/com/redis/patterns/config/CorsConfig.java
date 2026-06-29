package com.redis.patterns.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * Global CORS configuration for the application.
 *
 * This configuration allows cross-origin requests from an explicit allow-list of
 * origins (default: the local Angular frontend on localhost:4200 and the Spring Boot
 * backend on localhost:8080) to access the API. The list is overridable via the
 * APP_CORS_ALLOWED_ORIGINS environment variable / app.cors.allowed-origins property.
 *
 * An explicit allow-list is required here because credentials are enabled: the
 * wildcard "*" origin is forbidden by the CORS spec when allowCredentials is true.
 *
 * IMPORTANT: This remains a demo configuration. For production, you should:
 * - Restrict the allow-list to your real deployed front-end origin(s)
 * - Review and restrict allowed methods and headers
 * - Consider using Spring Security for more fine-grained control
 *
 * @author Redis Patterns Team
 */
@Configuration
public class CorsConfig {

    /**
     * Comma-separated list of allowed origins.
     * Defaults to the local frontend and backend; override via
     * APP_CORS_ALLOWED_ORIGINS / app.cors.allowed-origins.
     */
    @Value("${app.cors.allowed-origins:http://localhost:4200,http://localhost:8080}")
    private String allowedOrigins;

    /**
     * Creates a CORS filter that applies to all endpoints.
     *
     * This filter allows:
     * - An explicit allow-list of origins (from app.cors.allowed-origins)
     * - All HTTP methods (GET, POST, PUT, DELETE, etc.)
     * - All headers
     * - Credentials (cookies, authorization headers)
     *
     * @return CorsFilter configured for the demo
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // Explicit allow-list of origins (required because credentials are enabled)
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        config.setAllowedOrigins(List.copyOf(origins));

        // Allow credentials (cookies, authorization headers)
        config.setAllowCredentials(true);
        
        // Allow all HTTP methods
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        
        // Allow all headers
        config.setAllowedHeaders(Arrays.asList("*"));
        
        // Expose all headers to the client
        config.setExposedHeaders(Arrays.asList("*"));
        
        // Cache preflight response for 1 hour
        config.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        
        return new CorsFilter(source);
    }
}

