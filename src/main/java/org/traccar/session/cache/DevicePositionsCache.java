/*
 * Copyright 2015 - 2025 Anton Tananaev (anton@traccar.org)
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
package org.traccar.session.cache;

import jakarta.inject.Singleton;
import org.traccar.model.Position;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user, per-device cache for latest positions (GET /api/positions?deviceId=X).
 * Serves requests immediately from cache; revalidation is done in the background
 * by the caller (stale-while-revalidate). No streaming: responses are full lists.
 */
@Singleton
public class DevicePositionsCache {

    private static final long TTL_MILLIS = 300_000;  // 5 minutes

    private record Entry(List<Position> positions, long createdAt) {
        boolean isExpired(long nowMillis) {
            return (nowMillis - createdAt) >= TTL_MILLIS;
        }
    }

    private static String key(long userId, long deviceId) {
        return userId + ":" + deviceId;
    }

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    /**
     * Returns cached positions if present and not expired. Removes expired entry. Thread-safe.
     */
    public List<Position> get(long userId, long deviceId) {
        String k = key(userId, deviceId);
        Entry entry = cache.get(k);
        if (entry == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (entry.isExpired(now)) {
            cache.remove(k, entry);
            return null;
        }
        return entry.positions();
    }

    /**
     * Stores positions for the user/device. Thread-safe.
     */
    public void put(long userId, long deviceId, List<Position> positions) {
        cache.put(key(userId, deviceId), new Entry(positions, System.currentTimeMillis()));
    }

    /**
     * Removes all cached entries for the given device (e.g. after a new position is stored).
     */
    public void invalidateByDeviceId(long deviceId) {
        String suffix = ":" + deviceId;
        cache.keySet().removeIf(k -> k.endsWith(suffix));
    }
}
