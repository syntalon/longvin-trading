package com.longvin.dasgateway.cmdpublish;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class CmdOrderParser {

    private CmdOrderParser() {
    }

    public static Map<String, Object> parse(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.startsWith("%ORDER ") || trimmed.equals("%ORDER")) {
            return parsePercentOrder(trimmed);
        }
        if (trimmed.startsWith("%OrderAct ") || trimmed.equals("%OrderAct")) {
            return parsePercentOrderAct(trimmed);
        }
        if (trimmed.startsWith("%SLOrder ") || trimmed.equals("%SLOrder")) {
            return parsePercentSlOrder(trimmed);
        }
        if (trimmed.startsWith("%SLRET ") || trimmed.equals("%SLRET")) {
            return parsePercentSlRet(trimmed);
        }
        if (trimmed.startsWith("#SLOrder ") || trimmed.equals("#SLOrder")) {
            return parseSlOrderMarker(trimmed, "SL_ORDER_START");
        }
        if (trimmed.startsWith("#SLOrderEnd") || trimmed.equals("#SLOrderEnd")) {
            return parseSlOrderMarker(trimmed, "SL_ORDER_END");
        }
        if (trimmed.toUpperCase(Locale.ROOT).startsWith("NEWORDER ") || trimmed.equalsIgnoreCase("NEWORDER")) {
            return parseNewOrder(trimmed);
        }
        if (trimmed.toUpperCase(Locale.ROOT).startsWith("REPLACE ") || trimmed.equalsIgnoreCase("REPLACE")) {
            return parseReplace(trimmed);
        }
        return null;
    }

    private static Map<String, Object> parsePercentOrder(String line) {
        // Example (from manual):
        // %ORDER id token symb b/s mkt/lmt qty lvqty cxlqty price route status time origoid account trader orderSrc
        String[] p = line.split("\\s+");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventType", "ORDER");

        if (p.length >= 2) m.put("id", p[1]);
        if (p.length >= 3) m.put("token", p[2]);
        if (p.length >= 4) m.put("symbol", p[3]);
        if (p.length >= 5) m.put("side", p[4]);
        if (p.length >= 6) {
            String orderType = p[5];
            m.put("orderType", orderType);
            m.put("stopOrder", isStopOrderType(orderType));
        }
        if (p.length >= 7) m.put("qty", p[6]);
        if (p.length >= 8) m.put("leftQty", p[7]);
        if (p.length >= 9) m.put("canceledQty", p[8]);
        if (p.length >= 10) m.put("price", p[9]);
        if (p.length >= 11) m.put("route", p[10]);
        if (p.length >= 12) m.put("status", p[11]);
        if (p.length >= 13) m.put("time", p[12]);
        if (p.length >= 14) m.put("origOrderId", p[13]);
        if (p.length >= 15) m.put("account", p[14]);
        if (p.length >= 16) m.put("trader", p[15]);
        if (p.length >= 17) m.put("orderSrc", p[16]);

        return m;
    }

    private static Map<String, Object> parsePercentSlOrder(String line) {
        // Manual lists short locate messages:
        // #SLOrder / #SLOrderEnd and %SLOrder lines.
        // Field layouts can vary by route/provider, so we parse conservatively.
        String[] p = line.split("\\s+");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventType", "SL_ORDER");

        // Heuristics:
        // %SLOrder <id?> <token?> <symbol?> ...
        if (p.length >= 2) m.put("field1", p[1]);
        if (p.length >= 3) m.put("token", p[2]);
        if (p.length >= 4) m.put("symbol", p[3]);
        if (p.length >= 5) m.put("field4", p[4]);
        if (p.length >= 6) m.put("field5", p[5]);

        return m;
    }

    private static Map<String, Object> parsePercentSlRet(String line) {
        // %SLRET is a return message for short locate operations.
        String[] p = line.split("\\s+");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventType", "SL_RET");

        // Keep a couple early fields as best-effort.
        if (p.length >= 2) m.put("field1", p[1]);
        if (p.length >= 3) m.put("field2", p[2]);
        if (p.length >= 4) m.put("field3", p[3]);

        return m;
    }

    private static Map<String, Object> parseSlOrderMarker(String line, String eventType) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventType", eventType);
        return m;
    }

    private static Map<String, Object> parsePercentOrderAct(String line) {
        // %OrderAct id ActionType B/S symbol qty price route time notes token
        String[] p = line.split("\\s+");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventType", "ORDER_ACT");

        if (p.length >= 2) m.put("id", p[1]);
        if (p.length >= 3) m.put("actionType", p[2]);
        if (p.length >= 4) m.put("side", p[3]);
        if (p.length >= 5) m.put("symbol", p[4]);
        if (p.length >= 6) m.put("qty", p[5]);
        if (p.length >= 7) m.put("price", p[6]);
        if (p.length >= 8) m.put("route", p[7]);
        if (p.length >= 9) m.put("time", p[8]);

        // notes can contain spaces in theory; as a safe minimum we store the raw tail
        if (p.length >= 10) {
            StringBuilder sb = new StringBuilder();
            for (int i = 9; i < p.length; i++) {
                if (i > 9) sb.append(' ');
                sb.append(p[i]);
            }
            m.put("notesAndToken", sb.toString());
        }

        return m;
    }

    private static boolean isStopOrderType(String orderType) {
        if (orderType == null) {
            return false;
        }
        String t = orderType.toUpperCase(Locale.ROOT);
        return t.contains("STOP");
    }

    private static Map<String, Object> parseNewOrder(String line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventType", "CMD_NEWORDER");

        Map<String, String> params = parseKeyValueParams(line);
        if (!params.isEmpty()) {
            m.put("params", params);
        }

        String orderType = firstNonNull(params,
                "Type", "TYPE", "OrderType", "ORDERTYPE", "TYP");
        if (orderType != null) {
            m.put("orderType", orderType);
            m.put("stopOrder", isStopOrderType(orderType));
        }

        putIfPresent(m, params, "Symbol", "SYMBOL");
        putIfPresent(m, params, "Side", "SIDE");
        putIfPresent(m, params, "Qty", "QTY");
        putIfPresent(m, params, "Price", "PRICE");
        putIfPresent(m, params, "StopPrice", "STOPPRICE", "StopPx", "STOPPX");

        return m;
    }

    private static Map<String, Object> parseReplace(String line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventType", "CMD_REPLACE");

        Map<String, String> params = parseKeyValueParams(line);
        if (!params.isEmpty()) {
            m.put("params", params);
        }

        String orderType = firstNonNull(params,
                "Type", "TYPE", "OrderType", "ORDERTYPE", "TYP");
        if (orderType != null) {
            m.put("orderType", orderType);
            m.put("stopOrder", isStopOrderType(orderType));
        }

        putIfPresent(m, params, "Id", "ID", "OrderId", "ORDERID");
        putIfPresent(m, params, "Token", "TOKEN");
        putIfPresent(m, params, "Symbol", "SYMBOL");
        putIfPresent(m, params, "Qty", "QTY");
        putIfPresent(m, params, "Price", "PRICE");
        putIfPresent(m, params, "StopPrice", "STOPPRICE", "StopPx", "STOPPX");

        return m;
    }

    private static Map<String, String> parseKeyValueParams(String line) {
        String[] p = line.split("\\s+");
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 1; i < p.length; i++) {
            String token = p[i];
            int eq = token.indexOf('=');
            if (eq <= 0 || eq == token.length() - 1) {
                continue;
            }
            String k = token.substring(0, eq);
            String v = token.substring(eq + 1);
            params.put(k, v);
        }
        return params;
    }

    private static void putIfPresent(Map<String, Object> out, Map<String, String> params, String... keys) {
        for (String k : keys) {
            String v = params.get(k);
            if (v != null && !v.isBlank()) {
                out.put(normalizeKey(keys[0]), v);
                return;
            }
        }
    }

    private static String normalizeKey(String k) {
        if (k == null || k.isBlank()) {
            return k;
        }
        return Character.toLowerCase(k.charAt(0)) + k.substring(1);
    }

    private static String firstNonNull(Map<String, String> params, String... keys) {
        for (String k : keys) {
            String v = params.get(k);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
