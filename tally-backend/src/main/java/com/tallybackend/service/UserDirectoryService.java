package com.tallybackend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class UserDirectoryService {

    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final boolean bootstrapEnabled;
    private final String bootstrapUsername;
    private final String bootstrapPassword;
    private final String bootstrapDisplayName;
    private final String bootstrapRoles;
    private final boolean bootstrapActive;

    public UserDirectoryService(UserAccountRepository repository,
                                @Value("${auth.bootstrap.enabled:true}") boolean bootstrapEnabled,
                                @Value("${auth.bootstrap.username:admin}") String bootstrapUsername,
                                @Value("${auth.bootstrap.password:admin@123}") String bootstrapPassword,
                                @Value("${auth.bootstrap.display-name:Administrator}") String bootstrapDisplayName,
                                @Value("${auth.bootstrap.roles:ADMIN}") String bootstrapRoles,
                                @Value("${auth.bootstrap.active:true}") boolean bootstrapActive) {
        this.repository = repository;
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.bootstrapEnabled = bootstrapEnabled;
        this.bootstrapUsername = bootstrapUsername;
        this.bootstrapPassword = bootstrapPassword;
        this.bootstrapDisplayName = bootstrapDisplayName;
        this.bootstrapRoles = bootstrapRoles;
        this.bootstrapActive = bootstrapActive;
    }

    @PostConstruct
    public void init() {
        ensureBootstrapUser();
    }

    @Transactional
    public UserAccount authenticate(String username, String password) {
        String normalizedUsername = normalize(username);
        if (normalizedUsername == null || password == null) {
            return null;
        }
        Optional<UserAccount> accountOptional = repository.findByUsername(normalizedUsername);
        if (!accountOptional.isPresent()) {
            return null;
        }
        UserAccount account = accountOptional.get();
        if (!account.isActive() || account.getPasswordHash() == null) {
            return null;
        }
        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            return null;
        }

        Instant now = Instant.now();
        repository.touchLogin(normalizedUsername, now);
        if (account.getFirstSeenAt() == null) {
            account.setFirstSeenAt(now);
        }
        account.setLastSeenAt(now);
        return copy(account);
    }

    @Transactional
    public UserAccount upsert(String username,
                              String password,
                              String displayName,
                              List<String> roles,
                              Boolean active) {
        String normalizedUsername = normalize(username);
        if (normalizedUsername == null) {
            throw new IllegalArgumentException("username is required");
        }

        Optional<UserAccount> existingOptional = repository.findByUsername(normalizedUsername);
        UserAccount account = existingOptional.orElseGet(UserAccount::new);
        boolean created = !existingOptional.isPresent();
        Instant now = Instant.now();

        account.setUsername(normalizedUsername);
        if (created) {
            account.setFirstSeenAt(now);
        }

        if (password != null && !password.trim().isEmpty()) {
            account.setPasswordHash(passwordEncoder.encode(password));
        } else if (created) {
            throw new IllegalArgumentException("password is required for new users");
        }

        if (displayName != null) {
            account.setDisplayName(trimToNull(displayName));
        } else if (created && account.getDisplayName() == null) {
            account.setDisplayName(normalizedUsername);
        }

        if (roles != null) {
            account.setRoles(normalizeRoles(roles));
        } else if (created && account.getRoles() == null) {
            account.setRoles(new ArrayList<>());
        }

        if (active != null) {
            account.setActive(active);
        } else if (created) {
            account.setActive(true);
        }

        account.setLastSeenAt(now);
        if (created) {
            repository.insert(account);
        } else {
            repository.update(account);
        }
        return copy(account);
    }

    @Transactional
    public boolean deactivate(String username) {
        String normalizedUsername = normalize(username);
        if (normalizedUsername == null) {
            return false;
        }
        return repository.deactivate(normalizedUsername, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPublicUsers() {
        List<UserAccount> items = new ArrayList<>(repository.findAll());
        items.sort(Comparator.comparing(UserAccount::getUsername, String.CASE_INSENSITIVE_ORDER));
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserAccount user : items) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("username", user.getUsername());
            record.put("displayName", user.getDisplayName());
            record.put("roles", user.getRoles());
            record.put("active", user.isActive());
            record.put("firstSeenAt", user.getFirstSeenAt());
            record.put("lastSeenAt", user.getLastSeenAt());
            result.add(record);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public boolean exists(String username) {
        String normalizedUsername = normalize(username);
        return normalizedUsername != null && repository.exists(normalizedUsername);
    }

    private void ensureBootstrapUser() {
        if (!bootstrapEnabled || exists(bootstrapUsername) || bootstrapPassword == null || bootstrapPassword.trim().isEmpty()) {
            return;
        }
        UserAccount account = new UserAccount();
        account.setUsername(normalize(bootstrapUsername));
        account.setDisplayName(trimToNull(bootstrapDisplayName));
        account.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
        account.setRoles(parseRoles(bootstrapRoles));
        account.setActive(bootstrapActive);
        Instant now = Instant.now();
        account.setFirstSeenAt(now);
        account.setLastSeenAt(now);
        repository.insert(account);
    }

    private UserAccount copy(UserAccount source) {
        UserAccount target = new UserAccount();
        target.setUsername(source.getUsername());
        target.setDisplayName(source.getDisplayName());
        target.setRoles(source.getRoles());
        target.setActive(source.isActive());
        target.setFirstSeenAt(source.getFirstSeenAt());
        target.setLastSeenAt(source.getLastSeenAt());
        return target;
    }

    private List<String> parseRoles(String roles) {
        if (roles == null || roles.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String[] parts = roles.split(",");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String role = trimToNull(part);
            if (role != null) {
                result.add(role.toUpperCase(Locale.ROOT));
            }
        }
        return result;
    }

    private List<String> normalizeRoles(List<String> roles) {
        List<String> result = new ArrayList<>();
        for (String role : roles) {
            String normalized = trimToNull(role);
            if (normalized != null) {
                result.add(normalized.toUpperCase(Locale.ROOT));
            }
        }
        return result;
    }

    private String normalize(String value) {
        return UserDirectorySupport.normalizeUsername(value);
    }

    private String trimToNull(String value) {
        return UserDirectorySupport.trimToNull(value);
    }
}
