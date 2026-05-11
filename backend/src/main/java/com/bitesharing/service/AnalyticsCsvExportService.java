package com.bitesharing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class AnalyticsCsvExportService {

    private final ObjectMapper objectMapper;

    /**
     * Flattens a nested map/list structure into CSV rows (key,value) for spreadsheet import.
     */
    public byte[] toCsvBytes(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("key,value\n");
        flatten("", data, sb);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void flatten(String prefix, Object node, StringBuilder sb) {
        if (node == null) {
            sb.append(escape(prefix.isEmpty() ? "value" : prefix)).append(',').append('\n');
            return;
        }
        if (node instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                String next = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                flatten(next, e.getValue(), sb);
            }
            return;
        }
        if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(prefix + "[" + i + "]", list.get(i), sb);
            }
            return;
        }
        String val;
        if (node instanceof String || node instanceof Number || node instanceof Boolean) {
            val = node.toString();
        } else {
            try {
                val = objectMapper.writeValueAsString(node);
            } catch (Exception e) {
                val = String.valueOf(node);
            }
        }
        sb.append(escape(prefix)).append(',').append(escape(val)).append('\n');
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\"", "\"\"");
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s + "\"";
        }
        return s;
    }
}
