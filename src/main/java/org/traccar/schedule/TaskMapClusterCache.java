/*
 * Copyright 2025 Anton Tananaev (anton@traccar.org)
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
package org.traccar.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.MapCellRow;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Precomputes map clusters for all users at 3 zoom bands (zoomed out, mid, zoomed in)
 * and stores them in tc_map_clusters. Runs periodically so /api/positions/map can serve
 * from cache instead of running heavy ST_ClusterDBSCAN on every request.
 */
public class TaskMapClusterCache extends SingleScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskMapClusterCache.class);

    private static final double[] ZOOM_BAND_EPS_METERS = { 2000.0, 3000.0, 1000.0 };

    private final Storage storage;
    private final long periodSeconds;

    @Inject
    public TaskMapClusterCache(Storage storage, Config config) {
        this.storage = storage;
        this.periodSeconds = config.getLong(Keys.MAP_CLUSTER_CACHE_PERIOD);
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleAtFixedRate(this, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        try {
            if (!storage.hasPostGIS()) {
                return;
            }
            List<User> users = storage.getObjects(User.class, new Request(new Columns.Include("id")));
            for (User user : users) {
                try {
                    for (int zoomBand = 0; zoomBand < ZOOM_BAND_EPS_METERS.length; zoomBand++) {
                        double eps = ZOOM_BAND_EPS_METERS[zoomBand];
                        List<MapCellRow> clusters = storage.getMapClustersForUser(user.getId(), eps);
                        storage.saveMapClusters(user.getId(), zoomBand, clusters);
                    }
                } catch (StorageException e) {
                    LOGGER.warn("Map cluster cache failed for user {}", user.getId(), e);
                }
            }
            LOGGER.debug("Map cluster cache refresh completed for {} users", users.size());
        } catch (StorageException e) {
            LOGGER.warn("Map cluster cache refresh failed", e);
        }
    }

}
