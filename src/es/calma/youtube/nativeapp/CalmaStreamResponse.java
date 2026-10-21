package es.calma.youtube.nativeapp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates the pinned client's protobuf response without losing unknown fields. */
public final class CalmaStreamResponse {
    private CalmaStreamResponse() {}

    private static final class Field {
        int number, wire, start, payload, end;
        long value;
    }

    private static long varint(byte[] data, int[] cursor) {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (cursor[0] >= data.length) throw new IllegalArgumentException("Truncated varint");
            int next = data[cursor[0]++] & 255;
            if (shift == 63 && next > 1) throw new IllegalArgumentException("Overflow");
            value |= (long) (next & 127) << shift;
            if ((next & 128) == 0) return value;
        }
        throw new IllegalArgumentException("Invalid varint");
    }

    private static List<Field> fields(byte[] data) {
        List<Field> result = new ArrayList<>();
        int[] cursor = {0};
        while (cursor[0] < data.length) {
            if (result.size() >= 65536) throw new IllegalArgumentException("Too many fields");
            Field f = new Field(); f.start = cursor[0];
            long tag = varint(data, cursor);
            if (tag <= 0 || tag > 0xffffffffL || (tag >>> 3) == 0)
                throw new IllegalArgumentException("Invalid tag");
            f.number = (int) (tag >>> 3); f.wire = (int) (tag & 7);
            long length = 0;
            switch (f.wire) {
                case 0: f.value = varint(data, cursor); break;
                case 1: length = 8; break;
                case 2: length = varint(data, cursor); break;
                case 5: length = 4; break;
                default: throw new IllegalArgumentException("Unsupported wire type");
            }
            if (length < 0 || length > data.length - cursor[0])
                throw new IllegalArgumentException("Truncated field");
            f.payload = cursor[0]; cursor[0] += (int) length; f.end = cursor[0];
            result.add(f);
        }
        return result;
    }

    private static byte[] bytes(byte[] source, Field f) {
        return java.util.Arrays.copyOfRange(source, f.payload, f.end);
    }
    private static void original(ByteArrayOutputStream out, byte[] source, Field f) {
        out.write(source, f.start, f.end - f.start);
    }
    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~127L) != 0) { out.write((int) value & 127 | 128); value >>>= 7; }
        out.write((int) value);
    }
    private static void message(ByteArrayOutputStream out, int number, byte[] value) {
        writeVarint(out, (long) number << 3 | 2); writeVarint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static final class Format {
        final Field field;
        boolean addressable;
        String mime = "";
        long height;
        Format(byte[] streaming, Field field) {
            this.field = field;
            byte[] data = bytes(streaming, field);
            for (Field f : fields(data)) {
                if ((f.number == 2 || f.number == 48) && f.wire == 2 && f.end > f.payload)
                    addressable = true; // Direct URL or signatureCipher understood by the host.
                if (f.number == 5 && f.wire == 2)
                    mime = new String(data, f.payload, f.end - f.payload, StandardCharsets.UTF_8);
                if (f.number == 8 && f.wire == 0) height = f.value;
            }
        }
        boolean avc() { return mime.startsWith("video/") && mime.contains("avc1"); }
        boolean vp9() { return mime.startsWith("video/") && (mime.contains("vp9") || mime.contains("vp09")); }
    }

    private static byte[] cleanStreamingData(byte[] data) {
        List<Field> entries = fields(data);
        List<Format> formats = new ArrayList<>();
        Set<Long> avcHeights = new HashSet<>(), vp9Heights = new HashSet<>();
        for (Field f : entries) {
            if (f.number != 3) continue;
            if (f.wire != 2) throw new IllegalArgumentException("Invalid adaptive format");
            Format format = new Format(data, f); formats.add(format);
            if (!format.addressable || format.height <= 0) continue;
            if (format.avc()) avcHeights.add(format.height);
            if (format.vp9()) vp9Heights.add(format.height);
        }
        // Do not lose a higher resolution to fix a low-resolution singleton VP9 ladder.
        boolean incompleteVp9 = vp9Heights.size() == 1 && avcHeights.size() > 1
                && avcHeights.containsAll(vp9Heights);
        Set<Integer> retained = new HashSet<>();
        for (Format format : formats)
            if (format.addressable && !(incompleteVp9 && format.vp9())) retained.add(format.field.start);
        if (retained.isEmpty()) return null; // Let the existing client loop try another response.
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (Field f : entries) if (f.number != 3 || retained.contains(f.start)) original(out, data, f);
        return out.toByteArray();
    }

    /** Called before a response is accepted and the fallback loop stops. Null means unusable. */
    public static byte[] clean(byte[] playerResponse) {
        if (playerResponse == null || playerResponse.length > 16 * 1024 * 1024) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(playerResponse.length);
            boolean found = false;
            for (Field f : fields(playerResponse)) {
                if (f.number == 4) {
                    if (found || f.wire != 2) return null;
                    found = true;
                    byte[] streaming = cleanStreamingData(bytes(playerResponse, f));
                    if (streaming == null) return null;
                    message(out, 4, streaming);
                } else original(out, playerResponse, f);
            }
            return found ? out.toByteArray() : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
