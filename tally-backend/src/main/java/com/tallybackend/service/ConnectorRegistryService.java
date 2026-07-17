package com.tallybackend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConnectorRegistryService {

    private final ObjectMapper objectMapper;
    private final String defaultBaseUrl;
    private final String defaultAgentKey;
    private final Path registryFile;
    private final Map<String, ConnectorRegistration> registrations = new ConcurrentHashMap<>();

    public ConnectorRegistryService(ObjectMapper objectMapper,
                                    @Value("${ingest.base-url:http://127.0.0.1:8082}") String defaultBaseUrl,
                                    @Value("${ingest.agent-key:local-dev-key}") String defaultAgentKey,
                                    @Value("${connector.registry.file:connector-registry.json}") String registryFilePath) {
        this.objectMapper = objectMapper;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultAgentKey = defaultAgentKey;
        this.registryFile = Paths.get(registryFilePath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        loadFromDisk();
        ensureDefaultRegistration();
        persistQuietly();
    }

    public List<ConnectorRegistration> list() {
        List<ConnectorRegistration> items = new ArrayList<>(registrations.values());
        items.sort(Comparator.comparing(ConnectorRegistration::getConnectorId, String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    public ConnectorRegistration get(String connectorId) {
        if (connectorId == null) {
            return null;
        }
        return registrations.get(normalizeId(connectorId));
    }

    public synchronized ConnectorRegistration register(ConnectorRegistration registration) {
        String connectorId = normalizeId(registration.getConnectorId());
        if (connectorId == null || connectorId.isEmpty()) {
            throw new IllegalArgumentException("connectorId is required");
        }
        if (isBlank(registration.getBaseUrl())) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        Instant now = Instant.now();
        ConnectorRegistration normalized = registrations.get(connectorId);
        boolean created = normalized == null;
        if (created) {
            normalized = new ConnectorRegistration();
            normalized.setConnectorId(connectorId);
            normalized.setFirstSeenAt(now);
        } else if (normalized.getFirstSeenAt() == null) {
            normalized.setFirstSeenAt(now);
        }
        normalized.setBaseUrl(trimToNull(registration.getBaseUrl()));
        normalized.setAgentKey(trimToNull(registration.getAgentKey()));
        normalized.setCompany(trimToNull(registration.getCompany()));
        normalized.setBranch(trimToNull(registration.getBranch()));
        normalized.setDescription(trimToNull(registration.getDescription()));
        normalized.setActive(registration.isActive());
        normalized.setLastSeenAt(now);
        registrations.put(connectorId, normalized);
        persistQuietly();
        return normalized;
    }

    public synchronized ConnectorRegistration deactivate(String connectorId) {
        ConnectorRegistration existing = get(connectorId);
        if (existing == null) {
            return null;
        }
        existing.setActive(false);
        existing.setLastSeenAt(Instant.now());
        persistQuietly();
        return existing;
    }

    public ResolvedConnectorTarget resolveById(String connectorId) {
        ConnectorRegistration registration = get(connectorId);
        if (registration == null || !registration.isActive()) {
            return null;
        }
        return toResolvedTarget(registration, "registry:id");
    }

    public ResolvedConnectorTarget resolveByCompany(String company) {
        String normalizedCompany = normalizeText(company);
        if (normalizedCompany == null) {
            return null;
        }
        ConnectorRegistration match = null;
        for (ConnectorRegistration registration : registrations.values()) {
            if (!registration.isActive()) {
                continue;
            }
            if (!normalizedCompany.equals(normalizeText(registration.getCompany()))) {
                continue;
            }
            if (match != null) {
                throw new IllegalStateException("Multiple active connector registrations match company: " + company);
            }
            match = registration;
        }
        return match == null ? null : toResolvedTarget(match, "registry:company");
    }

    public ResolvedConnectorTarget resolveByBranch(String branch) {
        String normalizedBranch = normalizeText(branch);
        if (normalizedBranch == null) {
            return null;
        }
        ConnectorRegistration match = null;
        for (ConnectorRegistration registration : registrations.values()) {
            if (!registration.isActive()) {
                continue;
            }
            if (!normalizedBranch.equals(normalizeText(registration.getBranch()))) {
                continue;
            }
            if (match != null) {
                throw new IllegalStateException("Multiple active connector registrations match branch: " + branch);
            }
            match = registration;
        }
        return match == null ? null : toResolvedTarget(match, "registry:branch");
    }

    public ResolvedConnectorTarget resolveBySelection(String connectorId, String company, String branch) {
        ResolvedConnectorTarget resolved = resolveById(connectorId);
        if (resolved != null) {
            return resolved;
        }
        resolved = resolveByCompany(company);
        if (resolved != null) {
            return resolved;
        }
        return resolveByBranch(branch);
    }

    public ResolvedConnectorTarget defaultTarget() {
        ConnectorRegistration registration = get("default");
        if (registration != null && registration.isActive()) {
            return toResolvedTarget(registration, "default");
        }
        return new ResolvedConnectorTarget(defaultBaseUrl, defaultAgentKey, "default", "default");
    }

    public String getRegistryFilePath() {
        return registryFile.toString();
    }

    private ResolvedConnectorTarget toResolvedTarget(ConnectorRegistration registration, String source) {
        String agentKey = trimToNull(registration.getAgentKey());
        if (agentKey == null) {
            agentKey = trimToNull(defaultAgentKey);
        }
        return new ResolvedConnectorTarget(
                trimToNull(registration.getBaseUrl()),
                agentKey,
                registration.getConnectorId(),
                source
        );
    }

    private void ensureDefaultRegistration() {
        if (isBlank(defaultBaseUrl)) {
            return;
        }
        ConnectorRegistration existing = registrations.get("default");
        Instant now = Instant.now();
        if (existing == null) {
            ConnectorRegistration registration = new ConnectorRegistration();
            registration.setConnectorId("default");
            registration.setBaseUrl(trimToNull(defaultBaseUrl));
            registration.setAgentKey(trimToNull(defaultAgentKey));
            registration.setDescription("Default connector target from application.properties");
            registration.setActive(true);
            registration.setFirstSeenAt(now);
            registration.setLastSeenAt(now);
            registrations.put("default", registration);
            return;
        }
        if (isBlank(existing.getBaseUrl())) {
            existing.setBaseUrl(trimToNull(defaultBaseUrl));
        }
        if (isBlank(existing.getAgentKey())) {
            existing.setAgentKey(trimToNull(defaultAgentKey));
        }
        if (existing.getDescription() == null) {
            existing.setDescription("Default connector target from application.properties");
        }
        if (!existing.isActive()) {
            existing.setActive(true);
        }
        if (existing.getFirstSeenAt() == null) {
            existing.setFirstSeenAt(now);
        }
        if (existing.getLastSeenAt() == null) {
            existing.setLastSeenAt(now);
        }
    }

    private void loadFromDisk() {
        if (!Files.isRegularFile(registryFile)) {
            return;
        }
        try {
            List<ConnectorRegistration> items = objectMapper.readValue(
                    registryFile.toFile(),
                    new TypeReference<List<ConnectorRegistration>>() {}
            );
            registrations.clear();
            if (items == null) {
                return;
            }
            for (ConnectorRegistration item : items) {
                if (item == null || isBlank(item.getConnectorId()) || isBlank(item.getBaseUrl())) {
                    continue;
                }
                item.setConnectorId(normalizeId(item.getConnectorId()));
                if (item.getFirstSeenAt() == null) {
                    item.setFirstSeenAt(Instant.now());
                }
                if (item.getLastSeenAt() == null) {
                    item.setLastSeenAt(item.getFirstSeenAt());
                }
                registrations.put(item.getConnectorId(), item);
            }
        } catch (IOException ignored) {
            // keep service available even if the file is malformed or missing
        }
    }

    private void persistQuietly() {
        try {
            Path parent = registryFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(registryFile.toFile(), list());
        } catch (IOException ignored) {
            // runtime registry still works even if persistence fails
        }
    }

    private String normalizeId(String connectorId) {
        String text = trimToNull(connectorId);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        String text = trimToNull(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return trimToNull(value) == null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
