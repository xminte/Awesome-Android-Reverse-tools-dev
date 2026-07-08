package com.ecapture.burp.export;

import com.ecapture.burp.event.CapturedEvent;
import com.ecapture.burp.event.MatchedHttpPair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Exports captured HTTP traffic as structured, AI-friendly JSON.
 */
public final class TrafficJsonExporter {

    private TrafficJsonExporter() {
    }

    public static void write(Path outputFile, List<MatchedHttpPair> pairs) throws IOException {
        Files.writeString(
                outputFile,
                toJson(pairs),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }

    static String toJson(List<MatchedHttpPair> pairs) {
        StringBuilder json = new StringBuilder(Math.max(1024, pairs.size() * 2048));
        json.append("{\n");
        appendStringField(json, "schema_version", "1.0", 1, true);
        appendStringField(json, "exported_at", Instant.now().toString(), 1, true);
        appendStringField(json, "source", "eCapture Burp Suite Extension", 1, true);
        indent(json, 1).append("\"pair_count\": ").append(pairs.size()).append(",\n");
        indent(json, 1).append("\"pairs\": [\n");

        for (int i = 0; i < pairs.size(); i++) {
            appendPair(json, pairs.get(i), 2);
            if (i < pairs.size() - 1) {
                json.append(',');
            }
            json.append('\n');
        }

        indent(json, 1).append("]\n");
        json.append("}\n");
        return json.toString();
    }

    private static void appendPair(StringBuilder json, MatchedHttpPair pair, int level) {
        indent(json, level).append("{\n");
        appendStringField(json, "id", pair.getUuid(), level + 1, true);
        appendStringField(json, "timestamp", pair.getTimestamp(), level + 1, true);
        appendStringField(json, "method", pair.getMethod(), level + 1, true);
        appendStringField(json, "host", pair.getHost(), level + 1, true);
        appendStringField(json, "url", pair.getUrl(), level + 1, true);
        appendStringField(json, "status", pair.getStatusCode(), level + 1, true);
        appendStringField(json, "process", pair.getProcessInfo(), level + 1, true);
        indent(json, level + 1).append("\"port\": ").append(pair.getPort()).append(",\n");
        indent(json, level + 1).append("\"https\": ").append(pair.isHttps()).append(",\n");
        indent(json, level + 1).append("\"complete\": ").append(pair.isComplete()).append(",\n");
        appendEventField(json, "request", pair.getRequest(), level + 1, true);
        appendEventField(json, "response", pair.getResponse(), level + 1, false);
        indent(json, level).append('}');
    }

    private static void appendEventField(StringBuilder json, String name, CapturedEvent event,
                                         int level, boolean comma) {
        indent(json, level).append('"').append(name).append("\": ");
        if (event == null) {
            json.append("null");
        } else {
            byte[] payload = event.getPayload();
            json.append("{\n");
            appendStringField(json, "event_id", event.getUuid(), level + 1, true);
            appendStringField(json, "event_type", event.getEventType().name(), level + 1, true);
            indent(json, level + 1).append("\"timestamp\": ").append(event.getTimestamp()).append(",\n");
            appendStringField(json, "source_ip", event.getSrcIp(), level + 1, true);
            indent(json, level + 1).append("\"source_port\": ").append(event.getSrcPort()).append(",\n");
            appendStringField(json, "destination_ip", event.getDstIp(), level + 1, true);
            indent(json, level + 1).append("\"destination_port\": ").append(event.getDstPort()).append(",\n");
            indent(json, level + 1).append("\"pid\": ").append(event.getPid()).append(",\n");
            appendStringField(json, "process_name", event.getProcessName(), level + 1, true);
            indent(json, level + 1).append("\"length\": ").append(event.getLength()).append(",\n");
            appendStringField(json, "payload_text",
                    payload == null ? null : new String(payload, StandardCharsets.UTF_8), level + 1, true);
            appendStringField(json, "payload_base64",
                    payload == null ? null : Base64.getEncoder().encodeToString(payload), level + 1, false);
            indent(json, level).append('}');
        }
        if (comma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static void appendStringField(StringBuilder json, String name, String value,
                                          int level, boolean comma) {
        indent(json, level).append('"').append(name).append("\": ");
        if (value == null) {
            json.append("null");
        } else {
            json.append('"').append(escapeJson(value)).append('"');
        }
        if (comma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 32);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
            }
        }
        return escaped.toString();
    }

    private static StringBuilder indent(StringBuilder json, int level) {
        return json.append("  ".repeat(level));
    }
}
