package com.tallybackend.sync.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Service
public class TallyXmlTransportService {

    private final RestTemplate restTemplate;
    private final String targetUrl;

    public TallyXmlTransportService(RestTemplate restTemplate,
                                    @Value("${tally.sync.target-url:${TALLY_SYNC_TARGET_URL:http://127.0.0.1:9000}}") String targetUrl) {
        this.restTemplate = restTemplate;
        this.targetUrl = targetUrl;
    }

    public String importXml(String xml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_XML);
        headers.setAccept(java.util.Collections.singletonList(MediaType.TEXT_XML));
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(targetUrl, new HttpEntity<String>(xml, headers), String.class);
            return response.getBody() == null ? "" : response.getBody();
        } catch (ResourceAccessException ex) {
            throw ex;
        }
    }

    public String getTargetUrl() {
        return targetUrl;
    }
}
