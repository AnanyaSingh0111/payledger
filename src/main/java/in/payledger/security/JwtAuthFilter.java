package in.payledger.security;

import in.payledger.entity.User;
import in.payledger.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7).trim());
                Long userId = Long.valueOf(claims.getSubject());

                // One primary-key lookup per request: a disabled user is locked out at once,
                // and the role comes from the database, not from a possibly stale token.
                User user = userRepository.findById(userId).filter(User::isActive).orElse(null);

                if (user != null) {
                    // principal = the user's id, so @PreAuthorize can compare it to the path variable
                    var auth = new UsernamePasswordAuthenticationToken(
                            user.getId(), null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (JwtException | IllegalArgumentException e) {
                // bad token: stay unauthenticated, the entry point returns 401
                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(request, response);
    }
}
