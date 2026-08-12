package com.aerocore.service;

import com.aerocore.dto.AuthResponse;
import com.aerocore.dto.LoginRequest;
import com.aerocore.dto.RegisterRequest;
import com.aerocore.exception.EmailAlreadyRegisteredException;
import com.aerocore.exception.InvalidCredentialsException;
import com.aerocore.model.Role;
import com.aerocore.model.User;
import com.aerocore.repository.UserRepository;
import com.aerocore.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();

        // Checked here for a decent error message; the unique constraint is what actually
        // guarantees it, because two simultaneous registrations both pass this check.
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }

        User user = new User(
                email,
                passwordEncoder.encode(request.password()),
                request.fullName().trim(),
                Role.USER);   // Admins are made by hand, never by signing up.

        return tokenFor(userRepository.save(user));
    }

    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();

        User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return tokenFor(user);
    }

    private AuthResponse tokenFor(User user) {
        return new AuthResponse(
                jwtService.issue(user),
                jwtService.getTokenLifetimeSeconds(),
                user.getEmail(),
                user.getRole().name());
    }
}
