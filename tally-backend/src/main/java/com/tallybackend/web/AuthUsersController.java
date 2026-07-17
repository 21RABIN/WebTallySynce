package com.tallybackend.web;

import com.tallybackend.service.UserAccount;
import com.tallybackend.service.UserDirectoryService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/users")
@SecurityRequirements
public class AuthUsersController {

    private final UserDirectoryService userDirectoryService;

    public AuthUsersController(UserDirectoryService userDirectoryService) {
        this.userDirectoryService = userDirectoryService;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("forbidden"));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("users", userDirectoryService.listPublicUsers());
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<?> upsert(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("forbidden"));
        }

        String username = firstText(payload, "username");
        String password = firstText(payload, "password");
        String displayName = firstText(payload, "display_name", "displayName");
        Boolean active = booleanValue(payload.get("active"));
        List<String> roles = parseRoles(payload.get("roles"));

        try {
            UserAccount account = userDirectoryService.upsert(username, password, displayName, roles,
                    active == null ? null : active);
            return ResponseEntity.ok(publicUser(account));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error(ex.getMessage()));
        }
    }

    @DeleteMapping("/{username}")
    public ResponseEntity<?> deactivate(@PathVariable("username") String username, HttpServletRequest request) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("forbidden"));
        }
        if (!userDirectoryService.deactivate(username)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("user_not_found"));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "user_deactivated");
        response.put("username", username);
        return ResponseEntity.ok(response);
    }

    private boolean isAdmin(HttpServletRequest request) {
        Object roles = request.getAttribute("auth.roles");
        if (roles instanceof Collection) {
            for (Object item : (Collection<?>) roles) {
                if (isAdminRole(item)) {
                    return true;
                }
            }
        } else if (roles != null && roles.getClass().isArray()) {
            Object[] items = (Object[]) roles;
            for (Object item : items) {
                if (isAdminRole(item)) {
                    return true;
                }
            }
        } else if (roles != null) {
            String text = String.valueOf(roles);
            for (String part : text.split(",")) {
                if (isAdminRole(part)) {
                    return true;
                }
            }
        }

        Object claims = request.getAttribute("auth.claims");
        if (claims instanceof Map) {
            Object claimRoles = ((Map<?, ?>) claims).get("roles");
            if (claimRoles instanceof Collection) {
                for (Object item : (Collection<?>) claimRoles) {
                    if (isAdminRole(item)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isAdminRole(Object value) {
        if (value == null) {
            return false;
        }
        return "ADMIN".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private Map<String, String> error(String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("detail", message);
        return error;
    }

    private String firstText(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    private Boolean booleanValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        if ("true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text) || "no".equalsIgnoreCase(text) || "off".equalsIgnoreCase(text)) {
            return false;
        }
        return null;
    }

    private List<String> parseRoles(Object value) {
        List<String> roles = new ArrayList<>();
        if (value == null) {
            return roles;
        }
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                addRole(roles, item);
            }
            return roles;
        }
        if (value.getClass().isArray()) {
            Object[] items = (Object[]) value;
            for (Object item : items) {
                addRole(roles, item);
            }
            return roles;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return roles;
        }
        for (String part : text.split(",")) {
            addRole(roles, part);
        }
        return roles;
    }

    private void addRole(List<String> roles, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return;
        }
        roles.add(text.toUpperCase(Locale.ROOT));
    }

    private Map<String, Object> publicUser(UserAccount account) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("username", account.getUsername());
        response.put("displayName", account.getDisplayName());
        response.put("roles", account.getRoles());
        response.put("active", account.isActive());
        response.put("firstSeenAt", account.getFirstSeenAt());
        response.put("lastSeenAt", account.getLastSeenAt());
        return response;
    }
}
