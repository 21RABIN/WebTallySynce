package com.tallybackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthTokenServiceTest {

    @Test
    void revokedTokenCannotBeVerifiedAgain() {
        AuthTokenService service = new AuthTokenService(
                new ObjectMapper(),
                "test-secret",
                3600,
                false,
                "ignored"
        );

        Map<String, Object> issued = service.issueToken("alice", "tally-api", "issuer", "scope");
        String token = String.valueOf(issued.get("access_token"));

        assertNotNull(service.verifyToken(token, "tally-api"));
        service.revokeToken(token);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.verifyToken(token, "tally-api"));
        assertTrue(ex.getMessage().contains("revoked"));
    }
}
