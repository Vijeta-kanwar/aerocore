package com.aerocore.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads the bearer token off each request and, if it checks out, tells Spring Security who
 * is calling.
 *
 * <p>Note what it doesn't do: reject anything. A request with no token, or a bad one, simply
 * proceeds unauthenticated and the security rules decide whether that's allowed. Keeping the
 * "who are you" step separate from the "are you allowed" step is what lets flight search stay
 * public while cancellation doesn't.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);

        if (header != null && header.startsWith(PREFIX)) {
            jwtService.verify(header.substring(PREFIX.length()))
                    .ifPresent(user -> authenticate(user, request));
        }

        chain.doFilter(request, response);
    }

    private void authenticate(JwtService.AuthenticatedUser user, HttpServletRequest request) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role()));

        var authentication = new UsernamePasswordAuthenticationToken(user.userId(), null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        // The principal is the user id, not the whole User entity. Every request would
        // otherwise cost a database read to answer a question the token already answered.
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
