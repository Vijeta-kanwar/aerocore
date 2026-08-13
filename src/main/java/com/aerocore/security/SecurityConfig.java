package com.aerocore.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // CSRF protects cookie-authenticated browsers, where the browser attaches
                // credentials automatically and an attacker's page can ride along. A bearer
                // token has to be attached deliberately by our own JavaScript, so there is
                // nothing to ride. Disabled because it doesn't apply, not because it's noisy.
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(handling -> handling
                     .authenticationEntryPoint((request, response, ex) ->
                              response.sendError(
                                  HttpServletResponse.SC_UNAUTHORIZED,
                                 "Authentication required")))

                // Nothing to keep between requests. This is the setting that makes three
                // replicas interchangeable.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // Browsing the schedule needs no account. Requiring one would cost real
                        // users and protect data that is public by nature -- an airline
                        // publishes its timetable.
                        .requestMatchers(HttpMethod.GET, "/api/flights", "/api/flights/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/", "/index.html", "/app.js", "/styles.css", "/favicon.ico").permitAll()
                        .requestMatchers("/actuator/health/**", "/swagger-ui/**", "/swagger-ui.html",
                                         "/v3/api-docs/**").permitAll()

                        // Changing the schedule is another matter entirely.
                        .requestMatchers(HttpMethod.POST, "/api/flights").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/flights/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/flights/**").hasRole("ADMIN")

                        .anyRequest().authenticated())

                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * BCrypt, not SHA-256.
     *
     * <p>A general-purpose hash is built to be fast, which is exactly wrong here -- fast means
     * a leaked table can be brute-forced quickly. BCrypt is deliberately slow and salts every
     * hash individually, so identical passwords produce different output and a rainbow table
     * buys an attacker nothing.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
