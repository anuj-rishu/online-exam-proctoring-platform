package in.srminsider.labs.proctoring;

import in.srminsider.labs.auth.JwtUtil;
import in.srminsider.labs.domain.model.Role;
import in.srminsider.labs.domain.model.User;
import in.srminsider.labs.domain.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class JwtHandshakeInterceptorTest {

    private static final String TEST_USERNAME = "handshake-test-user@example.invalid";

    @Autowired
    private JwtHandshakeInterceptor interceptor;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void createUser() {
        userRepository.findByUsername(TEST_USERNAME).ifPresent(userRepository::delete);
        userRepository.save(User.builder()
                .username(TEST_USERNAME)
                .email(TEST_USERNAME)
                .password("{noop}unused")
                .role(Role.STUDENT)
                .year(1)
                .domain("Web Dev")
                .build());
    }

    @AfterEach
    void removeUser() {
        userRepository.findByUsername(TEST_USERNAME).ifPresent(userRepository::delete);
    }

    private boolean handshake(String query, Map<String, Object> attributes) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws-signaling");
        request.setQueryString(query);
        return interceptor.beforeHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                null,
                attributes);
    }

    @Test
    void acceptsValidTokenAndDerivesIdentityFromIt() {
        UserDetails principal = new org.springframework.security.core.userdetails.User(
                TEST_USERNAME, "unused",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_STUDENT")));
        String token = jwtUtil.generateToken(principal);

        Map<String, Object> attributes = new HashMap<>();
        assertTrue(handshake("token=" + token, attributes));
        assertEquals(TEST_USERNAME, attributes.get("username"));
        assertEquals(TEST_USERNAME, attributes.get("studentId"));
        assertEquals("student", attributes.get("role"));
    }

    @Test
    void rejectsMissingToken() {
        assertFalse(handshake(null, new HashMap<>()));
    }

    @Test
    void rejectsInvalidToken() {
        assertFalse(handshake("token=not-a-real-token", new HashMap<>()));
    }
}
