package com.github.epsilon.ysm.zstd;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET;

public final class ZstdUtil {

    private ZstdUtil() {}

    public static byte[] compress(byte[] input, int level) {
        if (level < 3 || level > 4) {
            level = 3;
        }
        CompressionParameters parameters = CompressionParameters.compute(level, -1);
        ZstdCompressor compressor = new ZstdCompressor();
        byte[] buffer = new byte[compressor.maxCompressedLength(input.length)];
        long inputAddress = ARRAY_BYTE_BASE_OFFSET;
        long inputLimit = inputAddress + input.length;
        long outputAddress = ARRAY_BYTE_BASE_OFFSET;
        long outputLimit = outputAddress + buffer.length;

        long output = outputAddress;
        output += ZstdFrameCompressor.writeMagic(buffer, output, outputLimit);
        output += ZstdFrameCompressor.writeFrameHeader(buffer, output, outputLimit, input.length, parameters.getWindowSize());
        output += ZstdFrameCompressor.compressFrame(input, inputAddress, inputLimit, buffer, output, outputLimit, parameters);
//        output += ZstdFrameCompressor.writeChecksum(buffer, output, outputLimit, input, inputAddress, inputLimit);

        int compressedSize = (int) (output - outputAddress);
        byte[] result = new byte[compressedSize];
        System.arraycopy(buffer, 0, result, 0, compressedSize);
        return result;
    }

    /** 解压后大小上限：帧头的 Frame_Content_Size 是外部可控的 64 位值，不能直接拿来分配。 */
    private static final long MAX_DECOMPRESSED_SIZE = 64L * 1024L * 1024L;

    public static byte[] decompress(byte[] input) {
        ZstdDecompressor decompressor = new ZstdDecompressor();
        long size = decompressor.getDecompressedSize(input, 0, input.length);
        // 超过上限或为负/截断值时改走流式路径（增量分配），避免 new byte[2GB] / NegativeArraySize / 静默空数组。
        if (size >= 0 && size <= MAX_DECOMPRESSED_SIZE) {
            byte[] output = new byte[(int) size];
            decompressor.decompress(input, 0, input.length, output, 0, output.length);
            return output;
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(input))) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) {
                    baos.write(buf, 0, read);
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new MalformedInputException(0, e.getMessage());
        }
    }
}
