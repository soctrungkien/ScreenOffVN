package com.tile.screenoff;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class HttpRequestParser {
    private byte[] rawData;

    public HttpRequestParser() {
        rawData = new byte[]{};
    }

    public void add(byte[] data) {
        if (data == null || data.length == 0) return;
        byte[] newBytes = new byte[rawData.length + data.length];
        System.arraycopy(rawData, 0, newBytes, 0, rawData.length);
        System.arraycopy(data, 0, newBytes, rawData.length, data.length);
        rawData = newBytes;
    }

    public void clear() {
        rawData = new byte[]{};
    }

    public HttpRequest parse() {
        if (rawData == null || rawData.length == 0) return null;

        String dataStr = new String(rawData, StandardCharsets.ISO_8859_1).replace("\r\n", "\n");
        String[] lines = dataStr.split("\n", -1);
        if (lines.length == 0 || lines[0].isEmpty()) return null;

        int emptyLineNum = findEmptyLine(lines);
        if (emptyLineNum <= 0 || emptyLineNum >= lines.length - 1) return null;

        HttpRequest.StartLine startLine = parseStartLine(lines[0]);
        if (startLine == null) return null;

        String[] headerField = Arrays.copyOfRange(lines, 1, emptyLineNum);
        Map<String, String> headers = parseRequestHeader(headerField);
        return new HttpRequest(startLine, headers);
    }

    private HttpRequest.StartLine parseStartLine(String startLine) {
        if (startLine == null) return null;
        String[] splitLine = startLine.trim().split("\\s+");
        if (splitLine.length < 2) return null;
        return new HttpRequest.StartLine(splitLine[1]);
    }

    private Map<String, String> parseRequestHeader(String[] headerField) {
        Map<String, String> result = new HashMap<>();
        if (headerField == null || headerField.length == 0) return result;

        for (String header : headerField) {
            String[] splitHeader = header.split(":", 2);
            if (splitHeader.length != 2) continue;
            result.put(splitHeader[0].trim().toLowerCase(Locale.ROOT), splitHeader[1].trim());
        }
        return result;
    }

    private int findEmptyLine(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isEmpty()) return i;
        }
        return -1;
    }
}
