package in.payledger.service;

import in.payledger.dto.AuthResponse;
import in.payledger.dto.LoginRequest;
import in.payledger.entity.User;
import in.payledger.repository.UserRepository;
import in.payledger.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse login(LoginRequest request) {

        // Same message for "no such user" and "wrong password", so emails can't be probed
        User user = userRepository.findByEmail(request.getEmail())
                .filter(u -> passwordEncoder.matches(
                        request.getPassword(), u.getPasswordHash()))
                .orElseThrow(() ->
                        new BadCredentialsException("Invalid email or password"));

        return new AuthResponse(jwtService.generateToken(user), user.getId());
    }
}