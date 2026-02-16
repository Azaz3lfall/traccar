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
import org.traccar.model.MapInitialResponse;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user in-memory cache for the initial map response (/api/positions/map).
 * First request loads from DB and stores the response; subsequent requests
 * within TTL are served from memory. Lazy expiration on read.
 */
@Singleton
public class MapInitialCache {

    private static final long TTL_MILLIS = 180_000;

    private record Entry(MapInitialResponse response, long createdAt) {
        boolean isExpired(long nowMillis) {
            return (nowMillis - createdAt) >= TTL_MILLIS;
        }
    }

    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    /**
     * Returns cached response for the user if present and not expired.
     * Removes expired entry. Thread-safe.
     */
    public MapInitialResponse get(long userId) {
        Entry entry = cache.get(userId);
        if (entry == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (entry.isExpired(now)) {
            cache.remove(userId, entry);
            return null;
        }
        return entry.response();
    }

    /**
     * Stores the response for the user. Thread-safe.
     */
    public void put(long userId, MapInitialResponse response) {
        cache.put(userId, new Entry(response, System.currentTimeMillis()));
    }
}
