package in.payledger.dto;

public class AuthResponse {

    private final String token;
    private final String tokenType;
    private final Long userId;

    public AuthResponse(String token, Long userId) {
        this.token = token;
        this.tokenType = "Bearer";
        this.userId = userId;
    }

    public String getToken() { return token; }
    public String getTokenType() { return tokenType; }
    public Long getUserId() { return userId; }
}