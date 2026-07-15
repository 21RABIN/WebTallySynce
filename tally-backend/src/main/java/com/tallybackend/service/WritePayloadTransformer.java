package com.tallybackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class WritePayloadTransformer {

    private static final Set<String> READ_ONLY_METADATA = new HashSet<>(Arrays.asList(
            "ISMSTDEPTYPE",
            "MSTDEPTYPE",
            "ISCMPDEPTYPE",
            "CMPDEPTYPE",
            "CMPLOCUS",
            "source_report",
            "source",
            "items"
    ));

    private final ObjectMapper objectMapper;

    public WritePayloadTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String transform(String connectorPath, HttpMethod method, String body) {
        if (body == null || body.trim().isEmpty() || HttpMethod.GET.equals(method)) {
            return body;
        }
        String trimmed = body.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return body;
        }

        try {
            JsonNode parsed = objectMapper.readTree(trimmed);
            if (shouldPassThrough(connectorPath, parsed)) {
                return objectMapper.writeValueAsString(parsed);
            }
            JsonNode transformed = transformNode(unwrapTopLevel(connectorPath, parsed));
            transformed = normalizeForConnectorPath(connectorPath, transformed);
            return objectMapper.writeValueAsString(transformed);
        } catch (Exception ignored) {
            return body;
        }
    }

    private boolean shouldPassThrough(String connectorPath, JsonNode node) {
        if (!node.isObject()) {
            return false;
        }
        String normalizedPath = connectorPath == null ? "" : connectorPath.trim().toLowerCase(Locale.ROOT);
        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        if (!normalizedPath.startsWith("vouchers/")) {
            return false;
        }
        boolean compliancePath = normalizedPath.contains("einvoice") || normalizedPath.contains("ewaybill");
        if (!compliancePath) {
            return false;
        }
        return node.has("voucher")
                || node.has("seller")
                || node.has("buyer")
                || node.has("items")
                || node.has("ewaybill")
                || node.has("dispatch_from")
                || node.has("ship_to");
    }

    private JsonNode normalizeForConnectorPath(String connectorPath, JsonNode transformed) {
        if (!(transformed instanceof ObjectNode)) {
            return transformed;
        }
        String normalizedPath = connectorPath == null ? "" : connectorPath.trim().toLowerCase(Locale.ROOT);
        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        if (normalizedPath.startsWith("stock-groups")) {
            ObjectNode objectNode = (ObjectNode) transformed;
            JsonNode parentNode = objectNode.get("PARENT");
            if (parentNode == null || parentNode.isNull()) {
                objectNode.remove("PARENT");
                return objectNode;
            }
            String parentValue = parentNode.asText("");
            if (parentValue == null || parentValue.trim().isEmpty() || "primary".equalsIgnoreCase(parentValue.trim())) {
                objectNode.remove("PARENT");
            }
        }
        return transformed;
    }

    private JsonNode unwrapTopLevel(String connectorPath, JsonNode node) {
        String normalized = connectorPath == null ? "" : connectorPath.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("vouchers") && node.isArray() && node.size() == 1) {
            return node.get(0);
        }
        if (!node.isObject()) {
            return node;
        }
        ObjectNode objectNode = (ObjectNode) node;
        String wrapperKey = wrapperKeyFor(connectorPath);
        if (wrapperKey != null && objectNode.has(wrapperKey)) {
            JsonNode wrapped = objectNode.get(wrapperKey);
            if (wrapped != null && wrapped.isArray() && wrapped.size() > 0) {
                return wrapped.get(0);
            }
            if (wrapped != null && !wrapped.isNull()) {
                return wrapped;
            }
        }
        return node;
    }

    private JsonNode transformNode(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : node) {
                arrayNode.add(transformNode(item));
            }
            return arrayNode;
        }

        ObjectNode source = (ObjectNode) node;
        if (source.size() == 1 && source.has("value")) {
            return transformNode(source.get("value"));
        }

        ObjectNode transformed = JsonNodeFactory.instance.objectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fieldName = entry.getKey();
            if (READ_ONLY_METADATA.contains(fieldName)) {
                continue;
            }
            JsonNode value = entry.getValue();
            if ("value".equals(fieldName) && source.size() > 1) {
                transformed.set("#text", transformNode(value));
                continue;
            }
            transformed.set(restoreConnectorKey(fieldName), transformNode(value));
        }
        return transformed;
    }

    private String restoreConnectorKey(String fieldName) {
        if (fieldName == null) {
            return "";
        }
        if ("LANGUAGENAME".equalsIgnoreCase(fieldName)) {
            return "LANGUAGENAME.LIST";
        }
        if ("QUANTITY".equalsIgnoreCase(fieldName)) {
            return "OPENINGBALANCE";
        }
        if ("NAME".equalsIgnoreCase(fieldName)) {
            return "NAME";
        }
        return fieldName;
    }

    private String wrapperKeyFor(String connectorPath) {
        String normalized = connectorPath == null ? "" : connectorPath.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty()) {
            return null;
        }

        if (normalized.startsWith("reports/companies") || normalized.startsWith("companies")) return "COMPANY";
        if (normalized.startsWith("groups")) return "GROUP";
        if (normalized.startsWith("ledgers")) return "LEDGER";
        if (normalized.startsWith("cost-categories")) return "COSTCATEGORY";
        if (normalized.startsWith("cost-centres") || normalized.startsWith("projects")) return "COSTCENTRE";
        if (normalized.startsWith("uoms")) return "UNIT";
        if (normalized.startsWith("godowns")) return "GODOWN";
        if (normalized.startsWith("stock-groups")) return "STOCKGROUP";
        if (normalized.startsWith("stock-categories")) return "STOCKCATEGORY";
        if (normalized.startsWith("stock-items")) return "STOCKITEM";
        if (normalized.startsWith("boms")) return "BOM";
        if (normalized.startsWith("voucher-types")) return "VOUCHERTYPE";
        if (normalized.startsWith("budgets")) return "BUDGET";
        if (normalized.startsWith("employees")) return "EMPLOYEE";
        if (normalized.startsWith("employee-groups")) return "EMPLOYEEGROUP";
        if (normalized.startsWith("pay-heads")) return "PAYHEAD";
        if (normalized.startsWith("attendance-types")) return "ATTENDANCETYPE";
        if (normalized.startsWith("currencies")) return "CURRENCY";
        if (normalized.startsWith("vouchers")) return "VOUCHER";
        return null;
    }
}
