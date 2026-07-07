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
package org.traccar.api.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.media.VideoStreamManager;
import org.traccar.model.Device;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

@Path("stream")
public class VideoStreamResource extends BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoStreamResource.class);

    @Inject
    private VideoStreamManager streamManager;

    @Inject
    private Config config;

    @Inject
    private ObjectMapper objectMapper;

    @GET
    @Path("{deviceId}/live.m3u8")
    public Response playlist(
            @PathParam("deviceId") long deviceId,
            @QueryParam("channel") @DefaultValue("1") int channel) throws StorageException {

        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        return Response.ok(streamManager.getPlaylist(deviceId, channel), "application/vnd.apple.mpegurl").build();
    }

    @GET
    @Path("{deviceId}/recordings")
    public Response recordings(@PathParam("deviceId") long deviceId) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        return Response.ok(streamManager.getRecordings(deviceId)).build();
    }

    @GET
    @Path("{deviceId}/{index}.ts")
    public Response segment(
            @PathParam("deviceId") long deviceId,
            @PathParam("index") int index,
            @QueryParam("channel") @DefaultValue("1") int channel) throws StorageException {

        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        ByteBuf data = streamManager.getSegment(deviceId, channel, index);
        StreamingOutput stream = output -> data.getBytes(data.readerIndex(), output, data.readableBytes());
        return Response.ok(stream, "video/mp2t").build();
    }

    /**
     * Drives the download of a recorded segment from the device's FTP server into the local media
     * folder, from where it is served (with range support) under {@code /api/media/...}. The segment
     * is identified by channel and start time (12-char BCD {@code yyMMddHHmmss}). The {@code size}
     * parameter is the recording's file size (from the recordings list) and is used to tell when the
     * device has finished uploading to FTP. Returns a small JSON status the frontend polls:
     * {@code pending} (not on FTP yet), {@code uploading} (device still uploading), {@code caching}
     * (server downloading/remuxing), {@code ready} (cached, with {@code url}).
     *
     * <p>The local cache is checked first, so a segment already downloaded is served instantly even
     * after the device's FTP copy has been deleted by its own housekeeping.
     */
    @GET
    @Path("{deviceId}/recording")
    public Response recording(
            @PathParam("deviceId") long deviceId,
            @QueryParam("channel") @DefaultValue("1") int channel,
            @QueryParam("start") String start,
            @QueryParam("size") @DefaultValue("0") long expectedSize) throws StorageException {

        permissionsService.checkPermission(Device.class, getUserId(), deviceId);

        if (start == null || start.length() != 12) {
            return status("error", Map.of("message", "invalid start time"));
        }

        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", deviceId)));
        if (device == null) {
            return status("error", Map.of("message", "device not found"));
        }

        File dir = new File(new File(config.getString(Keys.MEDIA_PATH), device.getUniqueId()), "jt1078");
        String prefix = "CH" + channel + "_" + start.substring(0, 6) + "_" + start.substring(6, 12) + "_";

        // 1. Already cached locally? Serve it without touching the (volatile) FTP copy.
        File cached = findLocal(dir, prefix, ".mp4");
        if (cached != null) {
            return status("ready", Map.of(
                    "url", "/api/media/" + device.getUniqueId() + "/jt1078/" + cached.getName(),
                    "size", cached.length()));
        }

        // 2. Download/remux already in progress for this segment?
        File partFile = findLocal(dir, prefix, ".part");
        if (partFile != null) {
            return status("caching", Map.of("cachedSize", partFile.length(), "ftpSize", 0));
        }

        // 3. Look it up on the device FTP and kick off the download.
        FtpConfig ftp = parseFtpConfig(device);
        if (ftp == null) {
            return status("error", Map.of("message", "no ftp configuration for device"));
        }
        try {
            Map<String, Long> listing = listFtp(ftp);
            String name = null;
            long ftpSize = 0;
            for (Map.Entry<String, Long> entry : listing.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    name = entry.getKey();
                    ftpSize = entry.getValue();
                    break;
                }
            }
            if (name == null) {
                return status("pending", Map.of("ftpSize", 0));
            }
            if (expectedSize > 0 && ftpSize < expectedSize) {
                return status("uploading", Map.of("ftpSize", ftpSize, "size", expectedSize));
            }
            String key = deviceId + "/" + name;
            if (streamManager.tryStartDownload(key)) {
                startDownload(ftp, name, dir, key);
            }
            return status("caching", Map.of("cachedSize", 0, "ftpSize", ftpSize));
        } catch (IOException e) {
            LOGGER.warn("Recording fetch failed for device {}", deviceId, e);
            return status("error", Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    private void startDownload(FtpConfig ftp, String name, File dir, String key) {
        Thread thread = new Thread(() -> {
            File partFile = new File(dir, name + ".part");
            File finalFile = new File(dir, name);
            try {
                dir.mkdirs();
                URI uri = URI.create(ftpUrl(ftp) + encodePath(name) + ";type=i");
                try (InputStream in = uri.toURL().openStream();
                        OutputStream out = new FileOutputStream(partFile)) {
                    in.transferTo(out);
                }
                if (faststart(partFile, finalFile)) {
                    partFile.delete();
                } else {
                    // ffmpeg unavailable or failed: serve the raw file as-is.
                    Files.move(partFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                LOGGER.info("Cached recording {} ({} bytes)", name, finalFile.length());
            } catch (IOException e) {
                LOGGER.warn("Recording download failed for {}", name, e);
                partFile.delete();
            } finally {
                streamManager.finishDownload(key);
            }
        }, "jt1078-download-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Remux to a web-friendly MP4 (moov atom at the front) so the browser can start playback
     * immediately instead of stalling to fetch the index from the end of the file. Video is copied;
     * audio is transcoded to AAC because device recordings usually carry G.711, which browsers
     * cannot play. Returns {@code false} if ffmpeg is not configured/available or the remux fails.
     */
    private boolean faststart(File src, File dst) {
        String ffmpeg = config.getString(Keys.MEDIA_FFMPEG_PATH);
        if (ffmpeg == null || !new File(ffmpeg).canExecute()) {
            return false;
        }
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        try {
            Process process = new ProcessBuilder(
                    ffmpeg, "-y", "-v", "error", "-i", src.getAbsolutePath(),
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "32k",
                    "-movflags", "+faststart", "-f", "mp4", tmp.getAbsolutePath())
                    .redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            if (process.waitFor() == 0 && tmp.length() > 0) {
                Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
            LOGGER.warn("Faststart remux failed for {}: {}", src.getName(), new String(output, StandardCharsets.UTF_8));
            tmp.delete();
            return false;
        } catch (IOException e) {
            tmp.delete();
            return false;
        } catch (InterruptedException e) {
            tmp.delete();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** First file in {@code dir} whose name starts with {@code prefix} and ends with {@code suffix}. */
    private File findLocal(File dir, String prefix, String suffix) {
        File[] files = dir.listFiles((d, n) -> n.startsWith(prefix) && n.endsWith(suffix));
        return files != null && files.length > 0 ? files[0] : null;
    }

    private Map<String, Long> listFtp(FtpConfig ftp) throws IOException {
        Map<String, Long> result = new HashMap<>();
        URI uri = URI.create(ftpUrl(ftp) + ";type=d");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(uri.toURL().openStream(), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 9) {
                    String fileName = parts[parts.length - 1];
                    long size = 0;
                    try {
                        size = Long.parseLong(parts[4]);
                    } catch (NumberFormatException ignored) {
                        size = 0;
                    }
                    result.put(fileName, size);
                }
            }
        }
        return result;
    }

    private FtpConfig parseFtpConfig(Device device) {
        String raw = device.getString("iothub");
        if (raw == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            String host = node.path("ftpServerIp").asText(null);
            if (host == null) {
                return null;
            }
            int port = node.path("ftpPort").asInt(21);
            String user = node.path("ftpUser").asText("");
            String password = node.path("ftpPassword").asText("");
            return new FtpConfig(host, port, user, password);
        } catch (IOException e) {
            return null;
        }
    }

    private String ftpUrl(FtpConfig ftp) {
        String credentials = encodeComponent(ftp.user()) + ":" + encodeComponent(ftp.password());
        return "ftp://" + credentials + "@" + ftp.host() + ":" + ftp.port() + "/";
    }

    private String encodeComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String encodePath(String value) {
        return encodeComponent(value).replace("+", "%20");
    }

    private Response status(String status, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("status", status);
        return Response.ok(body).build();
    }

    private record FtpConfig(String host, int port, String user, String password) {
    }

}
