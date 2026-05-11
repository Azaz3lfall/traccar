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

import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user, per-device cache for positions: latest (no from/to) and range (from/to, optional geofence).
 * Same rules: 5 min TTL, stale-while-revalidate, invalidate on new position for device.
 */
@Singleton
public class DevicePositionsCache {

    private static final long TTL_MILLIS = 300_000;  // 5 minutes

    private record Entry(List<Position> positions, long createdAt) {
        boolean isExpired(long nowMillis) {
            return (nowMillis - createdAt) >= TTL_MILLIS;
        }
    }

    private static String keyLatest(long userId, long deviceId) {
        return userId + ":" + deviceId;
    }

    private static String keyRange(long userId, long deviceId, Date from, Date to, long geofenceId) {
        return userId + ":" + deviceId + ":" + from.getTime() + ":" + to.getTime() + ":" + geofenceId;
    }

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    private List<Position> getEntry(String k) {
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

    /** Latest positions (no from/to). */
    public List<Position> get(long userId, long deviceId) {
        return getEntry(keyLatest(userId, deviceId));
    }

    /** Range positions (from/to, optional geofenceId). */
    public List<Position> get(long userId, long deviceId, Date from, Date to, long geofenceId) {
        return getEntry(keyRange(userId, deviceId, from, to, geofenceId));
    }

    public void put(long userId, long deviceId, List<Position> positions) {
        cache.put(keyLatest(userId, deviceId), new Entry(positions, System.currentTimeMillis()));
    }

    public void put(long userId, long deviceId, Date from, Date to, long geofenceId, List<Position> positions) {
        cache.put(keyRange(userId, deviceId, from, to, geofenceId), new Entry(positions, System.currentTimeMillis()));
    }

    /**
     * Removes all cached entries for the given device (e.g. after a new position is stored).
     */
    public void invalidateByDeviceId(long deviceId) {
        String withColon = ":" + deviceId;
        cache.keySet().removeIf(k -> k.contains(withColon + ":") || k.endsWith(withColon));
    }
}
