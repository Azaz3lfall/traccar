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
import io.netty.buffer.Unpooled;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class VideoStreamManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoStreamManager.class);

    private static final int MAX_SEGMENTS = 5;

    private final Map<String, DeviceStream> streams = new ConcurrentHashMap<>();

    private final Map<Long, List<Recording>> recordings = new ConcurrentHashMap<>();

    private final Set<String> activeDownloads = ConcurrentHashMap.newKeySet();

    private final String ffmpegPath;

    @Inject
    public VideoStreamManager(Config config) {
        String path = config.getString(Keys.MEDIA_FFMPEG_PATH);
        ffmpegPath = path != null && new File(path).canExecute() ? path : null;
    }

    /**
     * Claims a download slot for the given key. Returns {@code true} if the caller should start the
     * download, or {@code false} if one is already in progress for that key. Callers must invoke
     * {@link #finishDownload(String)} when done.
     */
    public boolean tryStartDownload(String key) {
        return activeDownloads.add(key);
    }

    public void finishDownload(String key) {
        activeDownloads.remove(key);
    }

    public boolean isDownloading(String key) {
        return activeDownloads.contains(key);
    }

    /**
     * One recorded segment stored on the device (e.g. SD card). Times are kept as the raw
     * 12-character BCD strings ({@code yyMMddHHmmss}) reported by the device, so they can be
     * sent straight back in a playback request without any timezone conversion.
     */
    public record Recording(
            int channel, String startTime, String endTime, long alarm,
            int mediaType, int streamType, int storageType, long fileSize) {
    }

    public void setRecordings(long deviceId, List<Recording> list) {
        recordings.put(deviceId, list);
    }

    public List<Recording> getRecordings(long deviceId) {
        return recordings.getOrDefault(deviceId, List.of());
    }

    public void handleFrame(
            long deviceId, int channel, ByteBuf nalData, long timestamp, boolean isKeyFrame, int payloadType) {
        DeviceStream stream = streams.computeIfAbsent(deviceId + "_" + channel, k -> new DeviceStream());
        stream.addFrame(nalData, timestamp, isKeyFrame, payloadType);
    }

    /**
     * JT1078 audio frame (dataType 3). G.711 audio is transcoded to AAC via ffmpeg before muxing,
     * since browsers cannot decode G.711 inside HLS. Silently ignored when ffmpeg is unavailable
     * or the payload codec is unsupported (stream stays video-only, as before).
     */
    public void handleAudioFrame(long deviceId, int channel, ByteBuf data, long timestamp, int payloadType) {
        if (ffmpegPath == null) {
            return;
        }
        String inputFormat;
        switch (payloadType) {
            case 6 -> inputFormat = "alaw";
            case 7 -> inputFormat = "mulaw";
            default -> {
                return;
            }
        }
        DeviceStream stream = streams.computeIfAbsent(deviceId + "_" + channel, k -> new DeviceStream());
        stream.addAudio(data, timestamp, inputFormat, ffmpegPath);
    }

    public String getPlaylist(long deviceId, int channel) {
        DeviceStream stream = streams.get(deviceId + "_" + channel);
        return stream != null ? stream.getPlaylist(channel) : DeviceStream.EMPTY_PLAYLIST;
    }

    public void removeStream(long deviceId, int channel) {
        DeviceStream stream = streams.remove(deviceId + "_" + channel);
        if (stream != null) {
            stream.release();
        }
    }

    public ByteBuf getSegment(long deviceId, int channel, int index) {
        DeviceStream stream = streams.get(deviceId + "_" + channel);
        return stream != null ? stream.getSegment(index) : null;
    }

    static class DeviceStream {

        private final VideoStreamWriter writer = new VideoStreamWriter();
        private final LinkedHashMap<Integer, ByteBuf> segments = new LinkedHashMap<>();
        private final LinkedHashMap<Integer, Double> durations = new LinkedHashMap<>();
        private ByteBuf currentSegment;
        private int segmentIndex;
        private long firstTimestamp;
        private long segmentStartTimestamp;
        private long lastTimestamp;

        private AudioTranscoder transcoder;
        private boolean transcoderFailed;
        private long audioAnchorTimestamp;
        private long audioFramesSinceAnchor;
        private long lastAudioInputTimestamp;

        synchronized void addFrame(ByteBuf nalData, long timestamp, boolean isKeyFrame, int payloadType) {
            if (isKeyFrame && currentSegment != null) {
                finalizeSegment();
            }

            if (currentSegment == null) {
                currentSegment = Unpooled.buffer();
                if (firstTimestamp == 0) {
                    firstTimestamp = timestamp;
                }
                segmentStartTimestamp = timestamp;
            }

            lastTimestamp = timestamp;
            writer.write(currentSegment, nalData, timestamp - firstTimestamp, isKeyFrame, payloadType);
            drainAudio();
        }

        synchronized void addAudio(ByteBuf data, long timestamp, String inputFormat, String ffmpegPath) {
            if (transcoderFailed) {
                return;
            }
            if (transcoder == null) {
                try {
                    transcoder = new AudioTranscoder(ffmpegPath, inputFormat);
                    audioAnchorTimestamp = timestamp;
                } catch (Exception e) {
                    LOGGER.warn("Audio transcoder start failed", e);
                    transcoderFailed = true;
                    return;
                }
            }
            lastAudioInputTimestamp = timestamp;
            try {
                transcoder.feed(data);
            } catch (Exception e) {
                LOGGER.warn("Audio transcoder feed failed", e);
                transcoderFailed = true;
                transcoder.close();
                return;
            }
            drainAudio();
        }

        private void drainAudio() {
            if (transcoder == null) {
                return;
            }
            long frameMs = 1000L * AudioTranscoder.SAMPLES_PER_FRAME / AudioTranscoder.SAMPLE_RATE;
            byte[] frame;
            while ((frame = transcoder.poll()) != null) {
                // Each ADTS frame is a fixed number of samples, so its PTS advances by frame count
                // from an anchor RTP timestamp. Streaming sessions stop and restart (and some
                // devices reset their clock per session), so whenever the counted PTS drifts away
                // from the incoming RTP timestamps, re-anchor instead of playing audio out of sync.
                long ptsMs = audioAnchorTimestamp + audioFramesSinceAnchor * frameMs;
                if (Math.abs(ptsMs - lastAudioInputTimestamp) > 1000) {
                    audioAnchorTimestamp = lastAudioInputTimestamp;
                    audioFramesSinceAnchor = 0;
                    ptsMs = audioAnchorTimestamp;
                }
                // frames arriving before the first video keyframe have no segment to land in
                if (currentSegment != null) {
                    writer.writeAudio(currentSegment, frame, ptsMs - firstTimestamp);
                }
                audioFramesSinceAnchor++;
            }
        }

        private void finalizeSegment() {
            // Real segment duration from frame timestamps (ms). A hard-coded EXTINF of 3.0 lied to
            // the player when GOPs are short (e.g. the V6 main stream keyframes ~0.6s apart), which
            // broke hls.js live-edge timing and showed "No Video". Clamp bogus values to 3.0.
            double duration = (lastTimestamp - segmentStartTimestamp) / 1000.0;
            if (duration <= 0 || duration > 30) {
                duration = 3.0;
            }
            duration = Math.max(0.1, Math.round(duration * 1000.0) / 1000.0);

            segments.put(segmentIndex, currentSegment);
            durations.put(segmentIndex, duration);
            segmentIndex++;
            currentSegment = null;

            while (segments.size() > MAX_SEGMENTS) {
                Integer oldest = segments.keySet().iterator().next();
                segments.remove(oldest).release();
                durations.remove(oldest);
            }
        }

        synchronized void release() {
            if (transcoder != null) {
                transcoder.close();
                transcoder = null;
            }
            if (currentSegment != null) {
                currentSegment.release();
            }
            for (ByteBuf segment : segments.values()) {
                segment.release();
            }
        }

        static final String EMPTY_PLAYLIST =
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:0\n";

        synchronized String getPlaylist(int channel) {
            // Only expose segments already closed on a keyframe boundary. Finalizing the in-progress
            // segment on every poll used to cut mid-GOP, so a segment could start without a keyframe
            // and fail to decode (worse on short-GOP live streams).
            if (segments.isEmpty()) {
                return EMPTY_PLAYLIST;
            }

            int firstIndex = segments.keySet().iterator().next();

            double maxDuration = durations.values().stream().mapToDouble(Double::doubleValue).max().orElse(3.0);
            int targetDuration = Math.max(1, (int) Math.ceil(maxDuration));

            StringBuilder sb = new StringBuilder();
            sb.append("#EXTM3U\n");
            sb.append("#EXT-X-VERSION:3\n");
            sb.append("#EXT-X-TARGETDURATION:").append(targetDuration).append("\n");
            sb.append("#EXT-X-MEDIA-SEQUENCE:").append(firstIndex).append("\n");

            for (int key : segments.keySet()) {
                // Real per-segment duration (see finalizeSegment). Carry the channel on each segment
                // URL too: relative-URL resolution in the player drops the playlist's query string,
                // so without it the .ts requests fall back to the default channel and channel 2+
                // would never play.
                sb.append("#EXTINF:").append(durations.getOrDefault(key, 3.0)).append(",\n");
                sb.append(key).append(".ts?channel=").append(channel).append("\n");
            }

            return sb.toString();
        }

        synchronized ByteBuf getSegment(int index) {
            return segments.get(index);
        }
    }

}
