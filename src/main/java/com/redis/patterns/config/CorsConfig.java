package com.redis.patterns.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.Collections;

/**
 * Global CORS configuration for the application.
 * 
 * This configuration allows cross-origin requests from the Angular frontend
 * running on localhost:4200 to access the Spring Boot backend on localhost:8080.
 * 
 * IMPORTANT: This is a development configuration. For production, you should:
 * - Specify exact allowed origins instead of "*"
 * - Review and restrict allowed methods and headers
 * - Consider using Spring Security for more fine-grained control
 * 
 * @author Redis Patterns Team
 */
@Configuration
public class CorsConfig {

    /**
     * Creates a CORS filter that applies to all endpoints.
     * 
     * This filter allows:
     * - All origins (*)
     * - All HTTP methods (GET, POST, PUT, DELETE, etc.)
     * - All headers
     * - Credentials (cookies, authorization headers)
     * 
     * @return CorsFilter configured for development
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        
        // Allow all origins (for development)
        config.setAllowedOriginPatterns(Collections.singletonList("*"));
        
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

