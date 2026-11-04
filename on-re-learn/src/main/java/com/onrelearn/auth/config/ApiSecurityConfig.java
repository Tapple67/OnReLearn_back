package com.onrelearn.auth.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class ApiSecurityConfig {
    @Bean
    FilterRegistrationBean<JwtCookieAuthenticationFilter> jwtFilterRegistration(JwtCookieAuthenticationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // The filter runs only inside Spring Security's API chain.
        return registration;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtCookieAuthenticationFilter cookies) throws Exception {
        http.securityMatcher("/api/**")
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .csrf(csrf -> csrf.spa())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/system/health", "/api/auth/csrf", "/api/auth/login", "/api/auth/register", "/api/auth/logout").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(
                (request, response, exception) -> response.sendError(401)))
            .addFilterBefore(cookies, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
