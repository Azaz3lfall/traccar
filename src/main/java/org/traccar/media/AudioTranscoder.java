/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.media;

import io.netty.buffer.ByteBuf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Long-lived ffmpeg process converting a raw G.711 (a-law/mu-law) stream to ADTS AAC frames so the
 * audio can be muxed into the HLS transport stream (browsers cannot play G.711). Input is fed from
 * the JT1078 audio RTP payloads; output frames are drained via {@link #poll()}.
 */
public class AudioTranscoder {

    public static final int SAMPLE_RATE = 8000;
    public static final int SAMPLES_PER_FRAME = 1024;

    private final Process process;
    private final OutputStream stdin;
    private final Queue<byte[]> frames = new ConcurrentLinkedQueue<>();

    public AudioTranscoder(String ffmpegPath, String inputFormat) throws IOException {
        // probing disabled: with the defaults ffmpeg holds all output until the input probe
        // buffer fills (minutes at G.711 bitrate), so no audio would reach the live stream
        process = new ProcessBuilder(
                ffmpegPath, "-v", "quiet",
                "-probesize", "32", "-analyzeduration", "0", "-fflags", "nobuffer",
                "-f", inputFormat, "-ar", String.valueOf(SAMPLE_RATE), "-ac", "1", "-i", "pipe:0",
                "-c:a", "aac", "-b:a", "32k", "-flush_packets", "1", "-f", "adts", "pipe:1")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        stdin = process.getOutputStream();
        Thread reader = new Thread(this::readLoop, "audio-transcoder-" + process.pid());
        reader.setDaemon(true);
        reader.start();
    }

    public void feed(ByteBuf data) throws IOException {
        byte[] bytes = new byte[data.readableBytes()];
        data.getBytes(data.readerIndex(), bytes);
        stdin.write(bytes);
        stdin.flush();
    }

    public byte[] poll() {
        return frames.poll();
    }

    private void readLoop() {
        try (InputStream in = process.getInputStream()) {
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                pending.write(chunk, 0, read);
                byte[] data = pending.toByteArray();
                int offset = 0;
                while (data.length - offset >= 7) {
                    if ((data[offset] & 0xFF) != 0xFF || (data[offset + 1] & 0xF0) != 0xF0) {
                        offset++;
                        continue;
                    }
                    int frameLength = ((data[offset + 3] & 0x03) << 11)
                            | ((data[offset + 4] & 0xFF) << 3)
                            | ((data[offset + 5] & 0xE0) >>> 5);
                    if (frameLength < 7) {
                        offset++;
                        continue;
                    }
                    if (data.length - offset < frameLength) {
                        break;
                    }
                    frames.add(Arrays.copyOfRange(data, offset, offset + frameLength));
                    offset += frameLength;
                }
                pending.reset();
                pending.write(data, offset, data.length - offset);
            }
        } catch (IOException ignored) {
            // process closed
        }
    }

    public void close() {
        try {
            stdin.close();
        } catch (IOException ignored) {
            // already closed
        }
        process.destroy();
    }

}
