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
import org.traccar.helper.UnitsConverter;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Treats GPS drift / phantom-speed reports from devices with ignition explicitly OFF.
 *
 * Rule (driftThreshold default 20 km/h):
 *   - ignition is missing/true                       -> no-op
 *   - ignition == false and 0 < speed <= threshold   -> clamp speed to 0 (motion=false)
 *   - ignition == false and speed > threshold        -> discard the position
 *
 * Applies only to the protocols listed in filter.ignition.protocols
 * (default: gt06,osmand). Enabled globally via filter.ignition.enable
 * or per device via the same attribute.
 */
public class IgnitionSpeedFilterHandler extends BasePositionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(IgnitionSpeedFilterHandler.class);

    private final CacheManager cacheManager;
    private final boolean enabledDefault;
    private final Set<String> protocols;
    private final int defaultThresholdKmh;

    @Inject
    public IgnitionSpeedFilterHandler(Config config, CacheManager cacheManager) {
        this.cacheManager = cacheManager;
        this.enabledDefault = config.getBoolean(Keys.FILTER_IGNITION_ENABLE);
        this.defaultThresholdKmh = config.getInteger(Keys.FILTER_IGNITION_DRIFT_THRESHOLD);

        String list = config.getString(Keys.FILTER_IGNITION_PROTOCOLS);
        Set<String> parsed = new HashSet<>();
        if (list != null && !list.isBlank()) {
            Arrays.stream(list.split("[\\s,]+"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                    .forEach(parsed::add);
        }
        this.protocols = parsed;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        long deviceId = position.getDeviceId();

        Boolean enabled = AttributeUtil.lookup(cacheManager, Keys.FILTER_IGNITION_ENABLE, deviceId);
        boolean effectiveEnabled = enabled != null ? enabled : enabledDefault;
        if (!effectiveEnabled) {
            callback.processed(false);
            return;
        }

        String protocol = position.getProtocol();
        if (protocol == null
                || protocols.isEmpty()
                || !protocols.contains(protocol.toLowerCase(java.util.Locale.ROOT))) {
            callback.processed(false);
            return;
        }

        if (!position.hasAttribute(Position.KEY_IGNITION)) {
            callback.processed(false);
            return;
        }

        boolean ignition = position.getBoolean(Position.KEY_IGNITION);
        if (ignition) {
            callback.processed(false);
            return;
        }

        double speedKmh = UnitsConverter.kphFromKnots(position.getSpeed());
        if (speedKmh <= 0) {
            callback.processed(false);
            return;
        }

        Integer thresholdAttr = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_IGNITION_DRIFT_THRESHOLD, deviceId);
        int threshold = thresholdAttr != null ? thresholdAttr : defaultThresholdKmh;

        if (speedKmh <= threshold) {
            LOGGER.info(
                    "Ignition-off drift clamped (was {} km/h) protocol={} deviceId={}",
                    String.format(java.util.Locale.ROOT, "%.2f", speedKmh), protocol, deviceId);
            position.setSpeed(0);
            position.set(Position.KEY_MOTION, false);
            callback.processed(false);
        } else {
            LOGGER.info(
                    "Ignition-off noise discarded ({} km/h) protocol={} deviceId={}",
                    String.format(java.util.Locale.ROOT, "%.2f", speedKmh), protocol, deviceId);
            callback.processed(true);
        }
    }
}
