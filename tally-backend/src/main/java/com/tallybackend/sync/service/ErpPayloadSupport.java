package com.tallybackend.sync.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ErpPayloadSupport {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private ErpPayloadSupport() {
    }

    static String stringValue(Map<String, Object> payload, String... keys) {
        if (payload == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Object value = payload.get(key);
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                return ((String) value).trim();
            }
        }
        return null;
    }

    static Long longValue(Map<String, Object> payload, String... keys) {
        if (payload == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            if (value instanceof String) {
                try {
                    return Long.valueOf(((String) value).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    static BigDecimal decimalValue(Map<String, Object> payload, String... keys) {
        if (payload == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof BigDecimal) {
                return ((BigDecimal) value).setScale(2, RoundingMode.HALF_UP);
            }
            if (value instanceof Number) {
                return BigDecimal.valueOf(((Number) value).doubleValue()).setScale(2, RoundingMode.HALF_UP);
            }
            if (value instanceof String) {
                try {
                    return new BigDecimal(((String) value).trim()).setScale(2, RoundingMode.HALF_UP);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    static Boolean booleanValue(Map<String, Object> payload, String... keys) {
        if (payload == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            if (value instanceof String) {
                String normalized = ((String) value).trim().toLowerCase(Locale.ROOT);
                if ("true".equals(normalized) || "yes".equals(normalized) || "1".equals(normalized)) {
                    return true;
                }
                if ("false".equals(normalized) || "no".equals(normalized) || "0".equals(normalized)) {
                    return false;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> objectValue(Map<String, Object> payload, String... keys) {
        if (payload == null || keys == null) {
            return Collections.emptyMap();
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOfMaps(Map<String, Object> payload, String... keys) {
        if (payload == null || keys == null) {
            return Collections.emptyList();
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof List) {
                List<Map<String, Object>> items = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    if (item instanceof Map) {
                        items.add((Map<String, Object>) item);
                    }
                }
                return items;
            }
        }
        return Collections.emptyList();
    }

    static String resolvePartyName(Map<String, Object> payload, String nestedKey, String... directKeys) {
        String direct = stringValue(payload, directKeys);
        if (direct != null) {
            return direct;
        }
        Map<String, Object> nested = objectValue(payload, nestedKey);
        return stringValue(nested, "name", "ledgerName", "displayName");
    }

    static String tallyDate(Map<String, Object> payload, String... keys) {
        String text = stringValue(payload, keys);
        if (text == null) {
            return null;
        }
        return normalizeDate(text);
    }

    static String normalizeDate(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String value = text.trim();
        if (value.matches("\\d{8}")) {
            return value;
        }
        return LocalDate.parse(value).format(DATE_FORMAT);
    }

    static String xml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    static String amount(BigDecimal value) {
        if (value == null) {
            return "0.00";
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
