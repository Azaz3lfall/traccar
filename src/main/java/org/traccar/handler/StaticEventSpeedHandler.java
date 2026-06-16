/*
 * Copyright 2026 Custom Traccar Distribution
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
package org.traccar.handler;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.Locale;

/**
 * Clamps stale "frozen GPS" speed on event positions kept at the exact same coordinate as the
 * previous one (computed distance == 0).
 *
 * This happens when a door/alarm event re-uses the last known fix on a parked vehicle (e.g. the
 * GT06 0x26 packet): the position bypasses filter.distance via skipAttributes (alarm) but inherits
 * the frozen speed from the last real fix, so a parked vehicle shows up moving (e.g. 18 km/h) on a
 * door-open event. With distance == 0 the vehicle has not moved, so the inherited speed is bogus.
 *
 * The handler sets speed and motion to zero while keeping the position and its alarm intact. It runs
 * after DistanceHandler (which sets KEY_DISTANCE) and FilterHandler (so only kept positions are
 * touched) and before MotionHandler. The previous-position guard avoids touching a device's very
 * first fix, where distance is also 0.
 *
 * Enabled via filter.staticEventSpeed (config or device attribute).
 */
public class StaticEventSpeedHandler extends BasePositionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(StaticEventSpeedHandler.class);

    private final CacheManager cacheManager;
    private final boolean enabledDefault;

    @Inject
    public StaticEventSpeedHandler(Config config, CacheManager cacheManager) {
        this.cacheManager = cacheManager;
        this.enabledDefault = config.getBoolean(Keys.FILTER_STATIC_EVENT_SPEED);
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        long deviceId = position.getDeviceId();

        Boolean enabled = AttributeUtil.lookup(cacheManager, Keys.FILTER_STATIC_EVENT_SPEED, deviceId);
        boolean effectiveEnabled = enabled != null ? enabled : enabledDefault;

        if (effectiveEnabled
                && cacheManager.getPosition(deviceId) != null
                && position.hasAttribute(Position.KEY_DISTANCE)
                && position.getDouble(Position.KEY_DISTANCE) == 0
                && position.getSpeed() > 0) {
            LOGGER.info(
                    "Static event speed clamped (was {} kn) protocol={} deviceId={}",
                    String.format(Locale.ROOT, "%.2f", position.getSpeed()), position.getProtocol(), deviceId);
            position.setSpeed(0);
            position.set(Position.KEY_MOTION, false);
        }

        callback.processed(false);
    }
}
