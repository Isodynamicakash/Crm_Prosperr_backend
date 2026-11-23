package com.prosperr.crm.ai.application;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class WavChunksTest {
    static byte[] wav(int seconds) {
        int len = 16_000 * 2 * seconds;
        ByteBuffer b = ByteBuffer.allocate(44 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + len).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short) 1)
         .putShort((short) 1).putInt(16_000).putInt(32_000).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(len);
        return b.array();
    }

    @Test
    void shortNotesStayWhole_longNotesSplitIntoPiecesUnder30s() {
        assertThat(WavChunks.split(wav(20), 25)).hasSize(1);
        var parts = WavChunks.split(wav(70), 25);
        assertThat(parts).hasSize(3);
        assertThat(parts.get(0).length).isEqualTo(44 + 16_000 * 2 * 25);
        assertThat(new String(parts.get(2), 0, 4)).isEqualTo("RIFF");
        assertThat(parts.get(2).length).isEqualTo(44 + 16_000 * 2 * 20);
    }
}
