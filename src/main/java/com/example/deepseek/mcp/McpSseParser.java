package com.example.deepseek.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

public class McpSseParser {

    public static List<SseEvent> parse(String content) throws IOException {
        List<SseEvent> events = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new StringReader(content));

        String line;
        StringBuilder dataBuilder = new StringBuilder();
        String eventType = "message";
        String eventId = null;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (dataBuilder.length() > 0 || eventId != null) {
                    events.add(new SseEvent(eventType, eventId, dataBuilder.toString().strip()));
                }
                dataBuilder = new StringBuilder();
                eventType = "message";
                eventId = null;
                continue;
            }

            int colonIndex = line.indexOf(':');
            if (colonIndex == -1) {
                continue;
            }

            String field = line.substring(0, colonIndex);
            String value = colonIndex + 1 < line.length() ? line.substring(colonIndex + 1) : "";
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }

            switch (field) {
                case "event" -> eventType = value;
                case "data" -> {
                    if (dataBuilder.length() > 0) {
                        dataBuilder.append("\n");
                    }
                    dataBuilder.append(value);
                }
                case "id" -> eventId = value;
            }
        }

        if (dataBuilder.length() > 0 || eventId != null) {
            events.add(new SseEvent(eventType, eventId, dataBuilder.toString().strip()));
        }

        return events;
    }
}
