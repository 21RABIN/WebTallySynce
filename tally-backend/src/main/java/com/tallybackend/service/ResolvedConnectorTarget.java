package com.tallybackend.service;

public class ResolvedConnectorTarget {

    private final String baseUrl;
    private final String agentKey;
    private final String connectorId;
    private final String resolutionSource;

    public ResolvedConnectorTarget(String baseUrl, String agentKey, String connectorId, String resolutionSource) {
        this.baseUrl = baseUrl;
        this.agentKey = agentKey;
        this.connectorId = connectorId;
        this.resolutionSource = resolutionSource;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getAgentKey() {
        return agentKey;
    }

    public String getConnectorId() {
        return connectorId;
    }

    public String getResolutionSource() {
        return resolutionSource;
    }
}
