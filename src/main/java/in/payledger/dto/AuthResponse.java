package in.payledger.dto;

import in.payledger.entity.Role;

public record AuthResponse(String token, String tokenType, long expiresInSeconds, Long userId, Role role) {

    public AuthResponse(String token, long expiresInSeconds, Long userId, Role role) {
        this(token, "Bearer", expiresInSeconds, userId, role);
    }
}
