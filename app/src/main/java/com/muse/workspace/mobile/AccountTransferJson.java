package com.muse.workspace.mobile;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded strict JSON for the closed transfer schema (no Android permissive parsing). */
final class AccountTransferJson {
    private final String text;
    private int offset, nodes;
    private AccountTransferJson(String text) { this.text = text; }

    @SafeVarargs static <T> java.util.Set<T> set(T... values) {
        return new java.util.HashSet<>(java.util.Arrays.asList(values));
    }

    static Object parse(byte[] bytes) throws AccountTransferCrypto.TransferException {
        if (bytes == null || bytes.length == 0 || bytes.length > AccountTransferCrypto.MAX_PLAINTEXT_BYTES) throw invalid();
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            AccountTransferJson parser = new AccountTransferJson(text);
            Object result = parser.value(0); parser.space();
            if (parser.offset != text.length()) throw invalid();
            return result;
        } catch (CharacterCodingException error) { throw invalid(); }
    }

    static byte[] bytes(Object value) throws AccountTransferCrypto.TransferException {
        StringBuilder result = new StringBuilder(); write(result, value, 0);
        byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > AccountTransferCrypto.MAX_PLAINTEXT_BYTES) throw invalid();
        return bytes;
    }

    private Object value(int depth) throws AccountTransferCrypto.TransferException {
        if (depth > 16 || ++nodes > 120000) throw invalid();
        space(); if (offset >= text.length()) throw invalid();
        char kind = text.charAt(offset);
        if (kind == '"') return string();
        if (kind == '{') {
            offset++; Map<String,Object> result = new LinkedHashMap<>(); space();
            if (take('}')) return result;
            do {
                space(); if (offset >= text.length() || text.charAt(offset) != '"') throw invalid();
                String key = string(); space();
                if (!take(':') || result.containsKey(key)) throw invalid();
                result.put(key, value(depth + 1)); space();
                if (take('}')) return result;
            } while (take(','));
            throw invalid();
        }
        if (kind == '[') {
            offset++; List<Object> result = new ArrayList<>(); space();
            if (take(']')) return result;
            do { result.add(value(depth + 1)); space(); if (take(']')) return result; } while (take(','));
            throw invalid();
        }
        if (text.startsWith("true", offset)) { offset += 4; return true; }
        if (text.startsWith("false", offset)) { offset += 5; return false; }
        if (text.startsWith("null", offset)) { offset += 4; return null; }
        int start = offset;
        take('-');
        if (take('0')) { /* another digit is rejected by the caller's separator check */ }
        else {
            if (offset >= text.length() || text.charAt(offset) < '1' || text.charAt(offset) > '9') throw invalid();
            do { offset++; } while (offset < text.length() && text.charAt(offset) >= '0' && text.charAt(offset) <= '9');
        }
        try { return Long.parseLong(text.substring(start, offset)); }
        catch (NumberFormatException error) { throw invalid(); }
    }

    private String string() throws AccountTransferCrypto.TransferException {
        if (!take('"')) throw invalid();
        StringBuilder value = new StringBuilder();
        while (offset < text.length()) {
            char item = text.charAt(offset++);
            if (item == '"') { validUnicode(value); return value.toString(); }
            if (item < 0x20) throw invalid();
            if (item == '\\') {
                if (offset >= text.length()) throw invalid();
                char escaped = text.charAt(offset++);
                switch (escaped) {
                    case '"', '\\', '/' -> item = escaped;
                    case 'b' -> item = '\b'; case 'f' -> item = '\f'; case 'n' -> item = '\n';
                    case 'r' -> item = '\r'; case 't' -> item = '\t';
                    case 'u' -> {
                        if (offset + 4 > text.length()) throw invalid();
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            char hex = text.charAt(offset++);
                            int digit = hex >= '0' && hex <= '9' ? hex - '0'
                                : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                            if (digit < 0) throw invalid();
                            code = code * 16 + digit;
                        }
                        item = (char) code;
                    }
                    default -> throw invalid();
                }
            }
            value.append(item);
        }
        throw invalid();
    }

    private void space() { while (offset < text.length() && " \r\n\t".indexOf(text.charAt(offset)) >= 0) offset++; }
    private boolean take(char value) { if (offset < text.length() && text.charAt(offset) == value) { offset++; return true; } return false; }

    private static void write(StringBuilder out, Object value, int depth) throws AccountTransferCrypto.TransferException {
        if (depth > 16 || out.length() > AccountTransferCrypto.MAX_PLAINTEXT_BYTES) throw invalid();
        if (value == null || value instanceof Boolean || value instanceof Long || value instanceof Integer) { out.append(value); return; }
        if (value instanceof String string) {
            validUnicode(string); out.append('"');
            for (int i=0;i<string.length();i++) {
                char item=string.charAt(i);
                if (item=='"' || item=='\\') out.append('\\').append(item);
                else if (item < 0x20) { out.append("\\u"); String hex=Integer.toHexString(item); for(int pad=hex.length();pad<4;pad++)out.append('0');out.append(hex); }
                else out.append(item);
            }
            out.append('"'); return;
        }
        if (value instanceof Map<?,?> map) {
            out.append('{'); boolean first=true;
            for (var entry:map.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw invalid();
                if (!first) out.append(',');first=false;write(out,entry.getKey(),depth+1);out.append(':');write(out,entry.getValue(),depth+1);
            }
            out.append('}');return;
        }
        if (value instanceof List<?> list) {
            out.append('[');boolean first=true;
            for(Object item:list){if(!first)out.append(',');first=false;write(out,item,depth+1);}out.append(']');return;
        }
        throw invalid();
    }

    static void validUnicode(CharSequence text) throws AccountTransferCrypto.TransferException {
        for(int i=0;i<text.length();i++) {
            char item=text.charAt(i);
            if(Character.isHighSurrogate(item)){if(i+1>=text.length()||!Character.isLowSurrogate(text.charAt(++i)))throw invalid();}
            else if(Character.isLowSurrogate(item))throw invalid();
        }
    }
    static AccountTransferCrypto.TransferException invalid() { return new AccountTransferCrypto.TransferException("payload"); }
}
