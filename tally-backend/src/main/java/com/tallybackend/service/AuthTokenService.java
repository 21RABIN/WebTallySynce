package com.tallybackend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthTokenService {

    private final ObjectMapper objectMapper;
    private final String tokenSecret;
    private final int tokenTtlSec;
    private final boolean acceptStaticAgentKey;
    private final String staticAgentKey;
    private final Map<String, Long> revokedTokenExpByJti = new ConcurrentHashMap<>();

    public AuthTokenService(ObjectMapper objectMapper,
                            @Value("${auth.token-secret:local-dev-key}") String tokenSecret,
                            @Value("${auth.token-ttl-sec:3600}") int tokenTtlSec,
                            @Value("${auth.accept-static-agent-key:true}") boolean acceptStaticAgentKey,
                            @Value("${ingest.agent-key:local-dev-key}") String staticAgentKey) {
        this.objectMapper = objectMapper;
        this.tokenSecret = tokenSecret;
        this.tokenTtlSec = tokenTtlSec;
        this.acceptStaticAgentKey = acceptStaticAgentKey;
        this.staticAgentKey = staticAgentKey;
    }

    public boolean acceptsStaticAgentKey(String incomingAgentKey) {
        return acceptStaticAgentKey && safeEquals(staticAgentKey, trimToEmpty(incomingAgentKey));
    }

    public Map<String, Object> issueToken(String subject, String audience, String issuer, String scope) {
        return issueToken(subject, audience, issuer, scope, null);
    }

    public Map<String, Object> issueToken(String subject,
                                          String audience,
                                          String issuer,
                                          String scope,
                                          Map<String, Object> extraClaims) {
        long issuedAt = System.currentTimeMillis() / 1000L;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", subject);
        payload.put("iss", issuer);
        payload.put("aud", audience);
        payload.put("iat", issuedAt);
        payload.put("exp", issuedAt + Math.max(tokenTtlSec, 1));
        payload.put("jti", UUID.randomUUID().toString().replace("-", ""));
        payload.put("scope", scope);
        if (extraClaims != null) {
            for (Map.Entry<String, Object> entry : extraClaims.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    payload.put(entry.getKey(), entry.getValue());
                }
            }
        }

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "TALLYTOKEN");

        try {
            String headerPart = base64Url(objectMapper.writeValueAsBytes(header));
            String payloadPart = base64Url(objectMapper.writeValueAsBytes(payload));
            String signingInput = headerPart + "." + payloadPart;
            String signaturePart = base64Url(hmac(signingInput));
            String token = signingInput + "." + signaturePart;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("token_type", "Bearer");
            response.put("expires_in", tokenTtlSec);
            response.put("expires_at", payload.get("exp"));
            response.put("issued_at", issuedAt);
            response.put("claims", payload);
            response.put("access_token", token);
            return response;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not issue token", ex);
        }
    }

    public Map<String, Object> verifyToken(String token, String expectedAudience) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Malformed token");
            }
            String signingInput = parts[0] + "." + parts[1];
            byte[] expectedSignature = hmac(signingInput);
            byte[] actualSignature = base64UrlDecode(parts[2]);
            if (!java.security.MessageDigest.isEqual(expectedSignature, actualSignature)) {
                throw new IllegalArgumentException("Invalid token signature");
            }

            Map<String, Object> payload = objectMapper.readValue(
                    base64UrlDecode(parts[1]),
                    new TypeReference<Map<String, Object>>() {}
            );
            long now = System.currentTimeMillis() / 1000L;
            Object exp = payload.get("exp");
            if (exp instanceof Number && now >= ((Number) exp).longValue()) {
                throw new IllegalArgumentException("Token expired");
            }
            Object aud = payload.get("aud");
            if (expectedAudience != null && aud != null) {
                String audValue = String.valueOf(aud);
                if (!expectedAudience.equals(audValue) && !"shared".equals(audValue)) {
                    throw new IllegalArgumentException("Invalid token audience");
                }
            }
            purgeExpiredRevocations(now);
            String jti = payload.get("jti") == null ? null : String.valueOf(payload.get("jti"));
            if (jti != null && revokedTokenExpByJti.containsKey(jti)) {
                throw new IllegalArgumentException("Token revoked");
            }
            return payload;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid token", ex);
        }
    }

    public void revokeToken(String token) {
        Map<String, Object> payload = verifyToken(token, null);
        String jti = payload.get("jti") == null ? null : String.valueOf(payload.get("jti"));
        Object exp = payload.get("exp");
        long expValue = exp instanceof Number ? ((Number) exp).longValue() : (System.currentTimeMillis() / 1000L) + tokenTtlSec;
        if (jti != null) {
            revokedTokenExpByJti.put(jti, expValue);
        }
    }

    private byte[] hmac(String signingInput) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
    }

    private String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private void purgeExpiredRevocations(long nowEpochSec) {
        revokedTokenExpByJti.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() <= nowEpochSec);
    }

    private boolean safeEquals(String left, String right) {
        return trimToEmpty(left).equals(trimToEmpty(right));
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
