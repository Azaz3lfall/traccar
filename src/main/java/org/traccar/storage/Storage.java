/*
 * Copyright 2022 - 2025 Anton Tananaev (anton@traccar.org)
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
package org.traccar.storage;

import org.traccar.model.BaseModel;
import org.traccar.model.MapBoundsRow;
import org.traccar.model.MapCellRow;
import org.traccar.model.Permission;
import org.traccar.model.PositionMapItem;
import org.traccar.model.PositionWithDevice;
import org.traccar.storage.query.Request;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public abstract class Storage {

    public abstract <T> List<T> getObjects(Class<T> clazz, Request request) throws StorageException;

    public abstract <T> Stream<T> getObjectsStream(Class<T> clazz, Request request) throws StorageException;

    public abstract <T> long addObject(T entity, Request request) throws StorageException;

    public abstract <T> void updateObject(T entity, Request request) throws StorageException;

    public abstract void removeObject(Class<?> clazz, Request request) throws StorageException;

    public abstract List<Permission> getPermissions(
            Class<? extends BaseModel> ownerClass, long ownerId,
            Class<? extends BaseModel> propertyClass, long propertyId) throws StorageException;

    public abstract void addPermission(Permission permission) throws StorageException;

    public abstract void removePermission(Permission permission) throws StorageException;

    public List<Permission> getPermissions(
            Class<? extends BaseModel> ownerClass,
            Class<? extends BaseModel> propertyClass) throws StorageException {
        return getPermissions(ownerClass, 0, propertyClass, 0);
    }

    public <T> T getObject(Class<T> clazz, Request request) throws StorageException {
        try (var objects = getObjectsStream(clazz, request)) {
            return objects.findFirst().orElse(null);
        }
    }

    /**
     * Returns latest positions in the given map bounds with device name and status.
     * Implemented only for PostgreSQL (direct optimized query). Others return empty list.
     */
    public List<PositionWithDevice> getPositionsInBoundsWithDevice(
            long userId, double minLat, double maxLat, double minLon, double maxLon) throws StorageException {
        return Collections.emptyList();
    }

    /**
     * Returns minimal position data for map view (id, deviceId, lat, lon, name, status) in bounds.
     * Implemented only for PostgreSQL. Others return empty list.
     */
    public List<PositionMapItem> getPositionsInBoundsForMapView(
            long userId, double minLat, double maxLat, double minLon, double maxLon) throws StorageException {
        return Collections.emptyList();
    }

    /**
     * Returns bounding box and count of all user's latest positions (for initial map load).
     * Implemented only for PostgreSQL. Others return null.
     */
    public MapBoundsRow getMapBoundsForUser(long userId)
            throws StorageException {
        return null;
    }

    /**
     * Returns one row per grid cell: count + centroid (avg lat/lon); for count=1 row also has id, deviceId,
     * name, status.
     * Clustering is done in DB. Implemented only for PostgreSQL. Others return empty list.
     */
    public List<MapCellRow> getMapCellsInBounds(
            long userId,
            double minLat,
            double maxLat,
            double minLon,
            double maxLon,
            double cellDeg) throws StorageException {
        return Collections.emptyList();
    }

    /**
     * True if the database has PostGIS extension (for distance-based clustering).
     */
    public boolean hasPostGIS() throws StorageException {
        return false;
    }

    /**
     * Returns one row per distance-based cluster (PostGIS ST_ClusterDBSCAN).
     * Points within epsMeters are in the same cluster; result shape same as getMapCellsInBounds.
     * Implemented only for PostgreSQL with PostGIS. Others return empty list.
     */
    public List<MapCellRow> getMapCellsInBoundsDistance(
            long userId,
            double minLat,
            double maxLat,
            double minLon,
            double maxLon,
            double epsMeters) throws StorageException {
        return Collections.emptyList();
    }

    /**
     * Returns precomputed map clusters for a user at a given zoom band (0=zoomed out, 1=mid, 2=zoomed in).
     * Used by scheduled task to populate cache. Implemented only for PostgreSQL with PostGIS.
     */
    public List<MapCellRow> getMapClustersForUser(long userId, double epsMeters) throws StorageException {
        return Collections.emptyList();
    }

    /**
     * Saves precomputed clusters for a user and zoom band. Replaces any existing cache for that user/band.
     * Implemented only for PostgreSQL.
     */
    public void saveMapClusters(long userId, int zoomBand, List<MapCellRow> clusters) throws StorageException {
    }

    /**
     * Returns precomputed map clusters from cache for the given user, zoom band, and bounds.
     * Implemented only for PostgreSQL. Returns empty list if cache is empty or not applicable.
     */
    public List<MapCellRow> getMapClustersFromCache(
            long userId, int zoomBand, double minLat, double maxLat, double minLon, double maxLon)
            throws StorageException {
        return Collections.emptyList();
    }

}
