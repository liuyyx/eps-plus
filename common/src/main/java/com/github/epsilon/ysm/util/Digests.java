package com.github.epsilon.ysm.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 摘要工具。替代 {@code org.apache.commons.codec.digest.DigestUtils}，
 * 输出格式与之一致（小写十六进制）。
 */
public final class Digests {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Digests() {
    }

    public static byte[] md5(byte[] data) {
        return digest("MD5", data);
    }

    public static String md5Hex(byte[] data) {
        return toHex(digest("MD5", data));
    }

    public static byte[] sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    public static String sha256Hex(byte[] data) {
        return toHex(digest("SHA-256", data));
    }

    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " not available", e);
        }
    }
}
