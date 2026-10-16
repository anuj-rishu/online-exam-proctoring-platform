package in.srminsider.labs.proctoring;

import in.srminsider.labs.auth.CustomUserDetailsService;
import in.srminsider.labs.auth.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {

        String token = extractToken(request.getURI());
        if (token == null) {
            reject(response);
            return false;
        }

        try {
            String username = jwtUtil.extractUsername(token);
            UserDetails user = userDetailsService.loadUserByUsername(username);

            if (!jwtUtil.validateToken(token, user)) {
                reject(response);
                return false;
            }

            attributes.put("username", username);
            attributes.put("studentId", username);
            attributes.put("role", resolveRole(user));
            
            
            attributes.put("superAdmin", isSuperAdmin(user));
            return true;

        } catch (Exception e) {
            log.warn("[Signaling] Rejected handshake: {}", e.getMessage());
            reject(response);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private String extractToken(URI uri) {
        String query = uri.getQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            int eq = param.indexOf('=');
            if (eq > 0 && "token".equals(param.substring(0, eq))) {
                String value = java.net.URLDecoder.decode(param.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private boolean isSuperAdmin(UserDetails user) {
        for (GrantedAuthority authority : user.getAuthorities()) {
            if ("ROLE_SUPER_ADMIN".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private String resolveRole(UserDetails user) {
        for (GrantedAuthority authority : user.getAuthorities()) {
            if ("ROLE_STUDENT".equals(authority.getAuthority())) {
                return "student";
            }
        }
        return "admin";
    }

    private void reject(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        if (response instanceof ServletServerHttpResponse servletResponse) {
            servletResponse.getServletResponse().setStatus(HttpStatus.UNAUTHORIZED.value());
        }
    }
}
