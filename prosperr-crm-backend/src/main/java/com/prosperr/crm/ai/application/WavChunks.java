package com.prosperr.crm.ai.application;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Splits a PCM WAV file into shorter WAV files of at most {@code seconds} each, cutting on sample boundaries. */
final class WavChunks {
    private WavChunks() {}

    static List<byte[]> split(byte[] wav, int seconds) {
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (wav.length < 44) return List.of(wav);
        int channels = 1, rate = 16_000, bits = 16, dataStart = -1, dataLen = 0;
        int pos = 12;
        while (pos + 8 <= wav.length) {
            String id = new String(wav, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = b.getInt(pos + 4);
            if (id.equals("fmt ")) {
                channels = b.getShort(pos + 10);
                rate = b.getInt(pos + 12);
                bits = b.getShort(pos + 22);
            } else if (id.equals("data")) {
                dataStart = pos + 8;
                dataLen = Math.min(size < 0 ? Integer.MAX_VALUE : size, wav.length - dataStart);
                break;
            }
            pos += 8 + size + (size & 1);
        }
        if (dataStart < 0 || rate <= 0 || channels <= 0 || bits <= 0) return List.of(wav);
        int frame = channels * bits / 8;
        int perChunk = rate * frame * seconds;
        if (dataLen <= perChunk) return List.of(wav);
        List<byte[]> out = new ArrayList<>();
        for (int off = 0; off < dataLen; off += perChunk) {
            int len = Math.min(perChunk, dataLen - off);
            if (len < rate * frame / 2) break;   // skip a last sliver under half a second
            ByteBuffer o = ByteBuffer.allocate(44 + len).order(ByteOrder.LITTLE_ENDIAN);
            o.put("RIFF".getBytes()).putInt(36 + len).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
             .putShort((short) 1).putShort((short) channels).putInt(rate).putInt(rate * frame).putShort((short) frame)
             .putShort((short) bits).put("data".getBytes()).putInt(len).put(wav, dataStart + off, len);
            out.add(o.array());
        }
        return out.isEmpty() ? List.of(wav) : out;
    }
}
