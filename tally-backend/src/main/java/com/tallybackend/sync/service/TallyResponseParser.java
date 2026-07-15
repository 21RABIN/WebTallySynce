package com.tallybackend.sync.service;

import com.tallybackend.sync.dto.TallyParsedResponse;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TallyResponseParser {
    private static final Pattern TAG_PATTERN_TEMPLATE = Pattern.compile("");

    public TallyParsedResponse parse(String responseXml) {
        TallyParsedResponse response = new TallyParsedResponse();
        response.setRawResponse(responseXml);
        response.setCreated(intTag(responseXml, "CREATED"));
        response.setAltered(intTag(responseXml, "ALTERED"));
        response.setDeleted(intTag(responseXml, "DELETED"));
        response.setErrors(intTag(responseXml, "ERRORS"));
        response.setCancelled(intTag(responseXml, "CANCELLED"));
        response.setLineError(stringTag(responseXml, "LINEERROR"));
        response.setLastVchId(stringTag(responseXml, "LASTVCHID"));
        response.setTallyGuid(firstNonBlank(stringTag(responseXml, "GUID"), stringTag(responseXml, "MASTERID")));
        response.setSuccess(response.getErrors() <= 0 && (response.getLineError() == null || response.getLineError().trim().isEmpty()));
        return response;
    }

    private int intTag(String xml, String tagName) {
        String value = stringTag(xml, tagName);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String stringTag(String xml, String tagName) {
        if (xml == null || tagName == null) {
            return null;
        }
        Pattern pattern = Pattern.compile("<" + tagName + ">(.*?)</" + tagName + ">", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(xml);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) == null ? null : matcher.group(1).trim();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return null;
    }
}
