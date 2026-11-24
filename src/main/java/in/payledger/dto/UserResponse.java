package in.payledger.dto;

import in.payledger.entity.Role;
import in.payledger.entity.User;
import in.payledger.entity.UserStatus;

import java.time.LocalDateTime;

public record UserResponse(Long id, String name, String email, Role role, UserStatus status, LocalDateTime createdAt) {

    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getName(), u.getEmail(), u.getRole(), u.getStatus(), u.getCreatedAt());
    }
}
