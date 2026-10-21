package es.calma.youtube.nativeapp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class PlaybackRegression {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    private static byte[] concat(byte[]... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] value : values) out.write(value, 0, value.length);
        return out.toByteArray();
    }
    private static byte[] var(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((value & ~127L) != 0) { out.write((int)value & 127 | 128); value >>>= 7; }
        out.write((int)value); return out.toByteArray();
    }
    private static byte[] message(int field, byte[] value) { return concat(var(field * 8 + 2), var(value.length), value); }
    private static byte[] text(int field, String value) { return message(field, value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] number(int field, long value) { return concat(var(field * 8), var(value)); }
    private static byte[] format(String mime, int height, String address, boolean cipher) {
        return message(3, concat(text(5, mime), number(8, height),
                address == null ? new byte[0] : text(cipher ? 48 : 2, address), text(100, "unknown-format-field")));
    }
    private static byte[] response(byte[]... formats) {
        return concat(text(101, "unknown-player-field"), message(2, number(1, 0)),
                message(4, concat(number(1, 3600), concat(formats), text(102, "unknown-stream-field"))));
    }
    public static void main(String[] args) {
        byte[] a144 = format("video/mp4; codecs=\"avc1\"", 144, "https://example.test/a144", false);
        byte[] a720 = format("video/mp4; codecs=\"avc1\"", 720, "https://example.test/a720", false);
        byte[] vp144 = format("video/webm; codecs=\"vp9\"", 144, "https://example.test/v144", false);
        byte[] vp720 = format("video/webm; codecs=\"vp09\"", 720, "https://example.test/v720", false);
        byte[] vp4k = format("video/webm; codecs=\"vp9\"", 2160, "https://example.test/v4k", false);
        byte[] audio = format("audio/mp4; codecs=\"mp4a\"", 0, "https://example.test/audio", false);
        byte[] placeholder = format("video/mp4", 1080, null, false);
        byte[] cipher = format("video/mp4; codecs=\"avc1\"", 1080, "url=encoded&sp=sig&s=signature", true);
        check(CalmaStreamResponse.clean(null) == null, "Null failure remains failure");
        check(CalmaStreamResponse.clean(new byte[0]) == null, "Empty response rejected");
        check(CalmaStreamResponse.clean(response(placeholder)) == null, "SABR-only formats do not stop fallback");
        check(CalmaStreamResponse.clean(response(format("video/mp4", 720, "", false))) == null, "Empty address rejected");
        check(Arrays.equals(CalmaStreamResponse.clean(response(a144, a720, audio)), response(a144, a720, audio)), "Valid response and unknown fields unchanged");
        check(Arrays.equals(CalmaStreamResponse.clean(response(placeholder, a144, audio)), response(a144, audio)), "Only unaddressable formats removed");
        check(Arrays.equals(CalmaStreamResponse.clean(response(a144, vp144, a720, audio)), response(a144, a720, audio)), "Incomplete low VP9 ladder cannot hide full AVC ladder");
        check(Arrays.equals(CalmaStreamResponse.clean(response(a144, vp144, a720, vp720, audio)), response(a144, vp144, a720, vp720, audio)), "Complete VP9 ladder retained");
        check(Arrays.equals(CalmaStreamResponse.clean(response(a144, vp4k, a720, audio)), response(a144, vp4k, a720, audio)), "Higher-resolution singleton retained");
        check(Arrays.equals(CalmaStreamResponse.clean(response(cipher, audio)), response(cipher, audio)), "Signature-cipher streams retained");
        check(Arrays.equals(CalmaStreamResponse.clean(response(audio)), response(audio)), "Audio-only adaptive response retained");
        check(CalmaStreamResponse.clean(new byte[]{0x22, 0x7f, 0x01}) == null, "Truncated nested message rejected");
        check(CalmaStreamResponse.clean(new byte[]{0}) == null, "Zero tag rejected");
        check(CalmaStreamResponse.clean(concat(response(a144), message(4, a720))) == null, "Ambiguous duplicate streaming data rejected");
        check(CalmaStreamResponse.clean(concat(var(4 * 8 + 2), new byte[]{-1,-1,-1,-1,-1,-1,-1,-1,-1,127})) == null, "Overflow length rejected");
        check(CalmaStreamResponse.clean(response(message(3, new byte[]{0x12,0x7f}))) == null, "Malformed format rejected");
        check(CalmaStreamResponse.clean(response(message(3, concat(text(2,"https://example.test/url"),new byte[]{3})))) == null, "Unsupported group rejected");
        System.out.println("PASS: " + checks + " playback regression checks");
    }
}
