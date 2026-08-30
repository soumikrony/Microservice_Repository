package com.example.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

class AuthControllerTest {

    @Test
    void tokenReturnsBearerTokenAndRoles() {
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        JwtEncoder jwtEncoder = mock(JwtEncoder.class);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken("alice", "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-token")
                        .header("alg", "HS256").claim("sub", "alice")
                        .build());

        var result = new AuthController(authenticationManager, jwtEncoder)
                .token(new LoginRequest("alice", "password"));

        assertThat(result).containsEntry("access_token", "test-token")
                .containsEntry("token_type", "Bearer")
                .containsEntry("expires_in", 3600);
        assertThat(result.get("roles")).asList().containsExactly("USER");
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }
}
