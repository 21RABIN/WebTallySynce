package com.tallybackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;

@Service
public class JsonShapeNormalizer {

    private static final String TEXT_FIELD = "#text";
    private static final String ATTRIBUTE_PREFIX = "@";
    private static final String LIST_SUFFIX = ".LIST";

    public JsonNode normalizeForRead(JsonNode node) {
        if (node == null || node.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (node.isArray()) {
            ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : node) {
                arrayNode.add(normalizeForRead(item));
            }
            return arrayNode;
        }
        if (!node.isObject()) {
            return node;
        }

        boolean hasText = node.has(TEXT_FIELD);
        JsonNode textNode = hasText ? node.get(TEXT_FIELD) : null;
        ObjectNode normalized = JsonNodeFactory.instance.objectNode();

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            JsonNode value = field.getValue();

            if (TEXT_FIELD.equals(name)) {
                continue;
            }

            if (name.startsWith(ATTRIBUTE_PREFIX)) {
                String sanitizedName = name.substring(1);
                if ("TYPE".equalsIgnoreCase(sanitizedName)) {
                    continue;
                }
                putMerged(normalized, sanitizeKey(sanitizedName), unwrapNestedDuplicate(sanitizeKey(sanitizedName), normalizeForRead(value)));
                continue;
            }

            String sanitizedName = sanitizeKey(name);
            putMerged(normalized, sanitizedName, unwrapNestedDuplicate(sanitizedName, normalizeForRead(value)));
        }

        if (hasText) {
            JsonNode normalizedText = normalizeForRead(textNode);
            if (normalized.size() == 0) {
                return normalizedText;
            }
            normalized.set("value", normalizedText);
        }

        return collapseSimpleObject(normalized);
    }

    public JsonNode normalizeForWriteResponse(JsonNode node) {
        return normalizeForRead(node);
    }

    private void putMerged(ObjectNode node, String fieldName, JsonNode value) {
        if (!node.has(fieldName)) {
            node.set(fieldName, value);
            return;
        }

        JsonNode existing = node.get(fieldName);
        ArrayNode merged = JsonNodeFactory.instance.arrayNode();
        if (existing.isArray()) {
            merged.addAll((ArrayNode) existing);
        } else {
            merged.add(existing);
        }
        if (value.isArray()) {
            merged.addAll((ArrayNode) value);
        } else {
            merged.add(value);
        }
        node.set(fieldName, merged);
    }

    private String sanitizeKey(String fieldName) {
        if (fieldName == null) {
            return "";
        }
        if (fieldName.endsWith(LIST_SUFFIX)) {
            return fieldName.substring(0, fieldName.length() - LIST_SUFFIX.length());
        }
        return fieldName;
    }

    private JsonNode unwrapNestedDuplicate(String fieldName, JsonNode value) {
        if (value != null && value.isObject() && value.size() == 1 && value.has(fieldName)) {
            return value.get(fieldName);
        }
        return value;
    }

    private JsonNode collapseSimpleObject(ObjectNode node) {
        if (node.size() == 1 && node.has("value")) {
            return node.get("value");
        }
        if (node.size() == 1) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            if (fields.hasNext()) {
                Map.Entry<String, JsonNode> single = fields.next();
                JsonNode value = single.getValue();
                if (value != null && !value.isContainerNode()) {
                    return value;
                }
            }
        }
        if (node.has("NAME") && node.has("value") && node.size() == 2) {
            JsonNode nameNode = node.get("NAME");
            JsonNode valueNode = node.get("value");
            if (nameNode != null && valueNode != null && nameNode.equals(valueNode)) {
                return nameNode;
            }
        }
        return node;
    }
}
