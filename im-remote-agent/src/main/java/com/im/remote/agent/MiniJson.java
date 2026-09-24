package com.im.remote.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析/序列化：只覆盖本协议需要的子集
 * （object / array / string / number / true / false / null）。
 *
 * <p>被控端刻意零第三方依赖（单个 jar 拷走就能跑），而协议 JSON 结构由
 * 我们自己定义，不需要宽容解析外部输入——解析失败一律抛
 * {@link IllegalArgumentException}，由帧分发处按「畸形帧」处理。
 * 数字统一：整数解析为 Long，带小数点/指数的解析为 Double。
 */
public final class MiniJson {

    private MiniJson() {
    }

    /* ==================== 解析 ==================== */

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.eof()) {
            throw new IllegalArgumentException("JSON 尾部存在多余字符, pos=" + parser.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("期望 JSON 对象, 实际: " + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        return (Map<String, Object>) value;
    }

    /** 取字符串字段，缺失返回 null */
    public static String str(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 取 long 字段（Number/字符串都容错），缺失返回 def */
    public static long lng(Map<String, Object> map, String key, long def) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    /** 取 int 字段 */
    public static int integer(Map<String, Object> map, String key, int def) {
        return (int) lng(map, key, def);
    }

    /** 取 boolean 字段 */
    public static boolean bool(Map<String, Object> map, String key, boolean def) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return def;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text == null ? "" : text;
        }

        boolean eof() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        Object readValue() {
            skipWhitespace();
            if (eof()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (!eof() && text.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                map.put(key, readValue());
                skipWhitespace();
                if (eof()) {
                    throw new IllegalArgumentException("JSON 对象未闭合");
                }
                char c = text.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("JSON 对象键值分隔符非法: " + c);
                }
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (!eof() && text.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (eof()) {
                    throw new IllegalArgumentException("JSON 数组未闭合");
                }
                char c = text.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("JSON 数组元素分隔符非法: " + c);
                }
            }
        }

        String readString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw new IllegalArgumentException("JSON 字符串未闭合");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c != '\\') {
                    builder.append(c);
                    continue;
                }
                if (eof()) {
                    throw new IllegalArgumentException("JSON 转义符后意外结束");
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw new IllegalArgumentException("JSON unicode 转义不完整");
                        }
                        builder.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("JSON 非法转义: \\" + esc);
                }
            }
        }

        Object readNumber() {
            int start = pos;
            boolean floating = false;
            if (!eof() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) {
                pos++;
            }
            while (!eof()) {
                char c = text.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating = floating || c == '.' || c == 'e' || c == 'E';
                    pos++;
                } else {
                    break;
                }
            }
            String raw = text.substring(start, pos);
            if (raw.isBlank()) {
                throw new IllegalArgumentException("JSON 非法值, pos=" + start);
            }
            return floating ? (Object) Double.parseDouble(raw) : (Object) Long.parseLong(raw);
        }

        Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, pos)) {
                throw new IllegalArgumentException("JSON 字面量非法, pos=" + pos);
            }
            pos += literal.length();
            return value;
        }

        void expect(char c) {
            if (eof() || text.charAt(pos) != c) {
                throw new IllegalArgumentException("JSON 期望 '" + c + "', pos=" + pos);
            }
            pos++;
        }
    }

    /* ==================== 序列化 ==================== */

    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(value, builder);
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(s, out);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                writeValue(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeValue(list.get(i), out);
            }
            out.append(']');
        } else {
            writeString(String.valueOf(value), out);
        }
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
