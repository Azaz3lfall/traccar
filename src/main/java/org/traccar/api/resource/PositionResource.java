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
package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.helper.model.PositionUtil;
import org.traccar.api.security.LoginService;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.model.MapBoundsRow;
import org.traccar.model.MapCellRow;
import org.traccar.model.MapInitialResponse;
import org.traccar.model.PositionCluster;
import org.traccar.model.PositionMapItem;
import org.traccar.model.PositionsMapResponse;
import org.traccar.model.User;
import org.traccar.model.UserRestrictions;
import org.traccar.reports.CsvExportProvider;
import org.traccar.reports.GpxExportProvider;
import org.traccar.reports.KmlExportProvider;
import org.traccar.session.cache.CacheManager;
import org.traccar.session.cache.DevicePositionsCache;
import org.traccar.session.cache.MapInitialCache;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

@Path("positions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PositionResource extends BaseResource {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(PositionResource.class);

    @Inject
    private KmlExportProvider kmlExportProvider;

    @Inject
    private CsvExportProvider csvExportProvider;

    @Inject
    private LoginService loginService;

    @Inject
    private CacheManager cacheManager;

    @Inject
    private GpxExportProvider gpxExportProvider;

    @Inject
    private MapInitialCache mapInitialCache;

    @Inject
    private DevicePositionsCache devicePositionsCache;

    @Inject
    private ExecutorService executorService;

    /** Zoom range for map: if client sends &lt; 1 or &gt; 22, we use closest (1 or 22). */
    private static final int MIN_ZOOM = 1;
    private static final int MAX_ZOOM = 22;
    /** Zoom ≥ 16: no clustering, return each position individually. */
    private static final int NO_CLUSTER_ZOOM_THRESHOLD = 16;
    /** Zoom 1–6: clusters only. Zoom 7–15: mixed (single points + clusters). Zoom 16–22: positions only. */
    private static final int MAX_ZOOM_CLUSTERS_ONLY = 6;

    /** Eps in meters by zoom (index 0..14 = zoom 0..15). Zoom 16+ = no clustering. Zoom 0–6 = big cluster (2000 km). */
    private static final double[] EPS_METERS_BY_ZOOM = {
        2_000_000.0, 2_000_000.0, 2_000_000.0, 2_000_000.0, 2_000_000.0, 2_000_000.0, 2_000_000.0,  /* zoom 0–6: big cluster */
        25_000.0, 12_000.0, 6_000.0, 3_000.0, 1_500.0, 800.0, 400.0,  /* zoom 7–13: region/city */
        200.0, 100.0   /* zoom 14–15: neighborhood */
    };

    /** Clamp zoom to [MIN_ZOOM, MAX_ZOOM]; out-of-range uses closest. */
    private static int clampZoom(int zoom) {
        if (zoom < MIN_ZOOM) return MIN_ZOOM;
        if (zoom > MAX_ZOOM) return MAX_ZOOM;
        return zoom;
    }

    /** Eps in meters for map-with-boundaries at given zoom. Used for zoom 0–15 only; zoom 16+ returns raw positions. */
    private static double epsMetersForMap(int zoom) {
        int z = zoom < 0 ? 0 : (zoom > 15 ? 15 : zoom);
        return EPS_METERS_BY_ZOOM[z];
    }

    /** Zoom level from longitude span (narrow bounds => higher zoom). */
    private static int zoomFromLonSpan(double lonSpan) {
        if (lonSpan <= 0) return MAX_ZOOM;
        int z = (int) Math.floor(Math.log(360.0 / Math.max(lonSpan, 0.001)) / Math.log(2));
        return clampZoom(z);
    }

    /** Expand bounds by factor on each side (e.g. 0.5 = 50% each side). Lat clamped to [-90,90], lon to [-180,180]. */
    private static Bounds expandBounds(double minLat, double maxLat, double minLon, double maxLon, double factor) {
        double latSpan = maxLat - minLat;
        double lonSpan = maxLon - minLon;
        double minLatExp = Math.max(-90, minLat - factor * latSpan);
        double maxLatExp = Math.min(90, maxLat + factor * latSpan);
        double minLonExp = Math.max(-180, minLon - factor * lonSpan);
        double maxLonExp = Math.min(180, maxLon + factor * lonSpan);
        return new Bounds(minLatExp, maxLatExp, minLonExp, maxLonExp);
    }

    private record Bounds(double minLat, double maxLat, double minLon, double maxLon) {}

    /** Default expand factor (50% each side) when not provided by client. */
    private static final double DEFAULT_EXPAND_FACTOR = 0.5;

    @GET
    public Response get(
            @QueryParam("deviceId") long deviceId, @QueryParam("id") List<Long> positionIds,
            @QueryParam("geofenceId") long geofenceId, @QueryParam("from") Date from, @QueryParam("to") Date to,
            @QueryParam("minLat") Double minLat, @QueryParam("maxLat") Double maxLat,
            @QueryParam("minLon") Double minLon, @QueryParam("maxLon") Double maxLon,
            @QueryParam("zoom") Integer zoom,
            @QueryParam("expandFactor") Double expandFactor)
            throws StorageException {
        boolean mapView = minLat != null && maxLat != null && minLon != null && maxLon != null
                && positionIds.isEmpty() && deviceId <= 0 && from == null && to == null;
        if (mapView) {
            long userId = getUserId();
            double factor = expandFactor != null ? Math.max(0, Math.min(2, expandFactor)) : DEFAULT_EXPAND_FACTOR;
            Bounds expanded = expandBounds(minLat, maxLat, minLon, maxLon, factor);
            double lonSpan = maxLon - minLon;
            int zoomFromBounds = zoomFromLonSpan(lonSpan);
            int effectiveZoom = clampZoom((zoom != null && zoom >= 0) ? zoom : zoomFromBounds);
            // Zoom 16–22: no clustering, return raw positions in bounds and empty clusters.
            if (effectiveZoom >= NO_CLUSTER_ZOOM_THRESHOLD) {
                List<PositionMapItem> positionsInBounds = storage.getPositionsInBoundsForMapView(
                        userId, expanded.minLat(), expanded.maxLat(), expanded.minLon(), expanded.maxLon());
                PositionsMapResponse mapResponse = new PositionsMapResponse(positionsInBounds, List.of());
                LOGGER.info("API /positions map-view: userId={} bounds=[{},{},{},{}] zoom={} (no cluster) -> {} positions",
                        userId, expanded.minLat(), expanded.maxLat(), expanded.minLon(), expanded.maxLon(), effectiveZoom, positionsInBounds.size());
                return Response.ok(mapResponse).build();
            }
            double epsMeters = epsMetersForMap(effectiveZoom);
            List<MapCellRow> cells = storage.getMapCellsInBoundsDistance(userId, expanded.minLat(), expanded.maxLat(), expanded.minLon(), expanded.maxLon(), epsMeters);
            LOGGER.info("API/positions map-view: userId={} bounds=[{},{},{},{}] zoom={} -> {} cells from DB",
                    userId, expanded.minLat(), expanded.maxLat(), expanded.minLon(), expanded.maxLon(), effectiveZoom, cells.size());
            // Zoom 1–6: clusters only. Zoom 7–15: single points for count=1, clusters for count>1.
            boolean includeSinglePoints = effectiveZoom > MAX_ZOOM_CLUSTERS_ONLY;
            PositionsMapResponse mapResponse = mapCellsToResponse(cells, includeSinglePoints);
            return Response.ok(mapResponse).build();
        }
        if (!positionIds.isEmpty()) {
            var positions = new ArrayList<Position>();
            for (long positionId : positionIds) {
                Position position = storage.getObject(Position.class, new Request(
                        new Columns.All(), new Condition.Equals("id", positionId)));
                permissionsService.checkPermission(Device.class, getUserId(), position.getDeviceId());
                positions.add(position);
            }
            return Response.ok(positions.stream()).build();
        } else if (deviceId > 0) {
            permissionsService.checkPermission(Device.class, getUserId(), deviceId);
            if (from != null && to != null) {
                permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);

                Geofence geofence = geofenceId == 0 ? null : storage.getObject(Geofence.class, new Request(
                        new Columns.All(), new Condition.Equals("id", geofenceId)));

                long userId = getUserId();
                List<Position> cached = devicePositionsCache.get(userId, deviceId, from, to, geofenceId);
                if (cached != null) {
                    executorService.submit(() -> revalidateDevicePositionsRange(userId, deviceId, from, to, geofenceId, geofence));
                    return Response.ok(cached).build();
                }
                List<Position> positions = loadPositionsRange(deviceId, from, to, geofence);
                devicePositionsCache.put(userId, deviceId, from, to, geofenceId, positions);
                return Response.ok(positions).build();
            } else {
                long userId = getUserId();
                List<Position> cached = devicePositionsCache.get(userId, deviceId);
                if (cached != null) {
                    executorService.submit(() -> revalidateDevicePositions(userId, deviceId));
                    return Response.ok(cached).build();
                }
                List<Position> positions = loadLatestPositions(deviceId);
                LOGGER.info("API /positions?deviceId={}: cache miss, loaded from DB ({} positions)", deviceId, positions.size());
                devicePositionsCache.put(userId, deviceId, positions);
                return Response.ok(positions).build();
            }
        } else {
            var userId = getUserId();
            var devices = storage.getObjects(Device.class, new Request(
                    new Columns.All(), new Condition.Permission(User.class, userId, Device.class)));

            var positions = new ArrayList<Position>();
            for (var device : devices) {
                var position = cacheManager.getPosition(device.getId());
                if (position != null) {
                    positions.add(position);
                }
            }
            LOGGER.info("API /positions: Returning {} positions from MEMORY cache.", positions.size());
            return Response.ok(positions.stream()).build();
        }
    }

    private List<Position> loadLatestPositions(long deviceId) throws StorageException {
        String turbo = permissionsService.getServer().getString("position.turbo", "24 hours");
        try (Stream<Position> stream = storage.getObjectsStream(Position.class, new Request(
                new Columns.All(), new Condition.LatestPositions(deviceId, 0, turbo)))) {
            return stream.toList();
        }
    }

    private List<Position> loadPositionsRange(long deviceId, Date from, Date to, Geofence geofence) throws StorageException {
        try (Stream<Position> stream = PositionUtil.getPositionsStream(storage, deviceId, from, to)
                .filter(p -> geofence == null || geofence.containsPosition(p))) {
            return stream.toList();
        }
    }

    private void revalidateDevicePositions(long userId, long deviceId) {
        try {
            List<Position> positions = loadLatestPositions(deviceId);
            devicePositionsCache.put(userId, deviceId, positions);
        } catch (Exception e) {
            LOGGER.warn("Background revalidation of positions for device {} failed", deviceId, e);
        }
    }

    private void revalidateDevicePositionsRange(long userId, long deviceId, Date from, Date to, long geofenceId, Geofence geofence) {
        try {
            List<Position> positions = loadPositionsRange(deviceId, from, to, geofence);
            devicePositionsCache.put(userId, deviceId, from, to, geofenceId, positions);
        } catch (Exception e) {
            LOGGER.warn("Background revalidation of positions range for device {} failed", deviceId, e);
        }
    }

    /** Converts DB cluster rows (one per cell) to positions list + clusters list. No grouping in memory.
     * When includeSinglePoints: count==1 → position, count&gt;1 → cluster. When false (zoom ≤5): all as clusters. */
    private static PositionsMapResponse mapCellsToResponse(List<MapCellRow> cells, boolean includeSinglePoints) {
        var positions = new ArrayList<PositionMapItem>();
        var clusters = new ArrayList<PositionCluster>();
        for (var row : cells) {
            if (includeSinglePoints && row.getCount() == 1) {
                PositionMapItem item = new PositionMapItem();
                item.setId(row.getId());
                item.setDeviceId(row.getDeviceId());
                item.setLatitude(row.getLatitude());
                item.setLongitude(row.getLongitude());
                item.setCourse(row.getCourse());
                item.setName(row.getName());
                item.setStatus(row.getStatus());
                item.setCategory(row.getCategory());
                positions.add(item);
            } else {
                clusters.add(new PositionCluster(row.getLatitude(), row.getLongitude(), (int) row.getCount()));
            }
        }
        return new PositionsMapResponse(positions, clusters);
    }

    /**
     * Initial map load: returns map boundaries, suggested zoom, device count,
     * and plot data (single positions + clusters) in one call.
     */
    @Path("map")
    @GET
    public Response getMapInitial(@QueryParam("expandFactor") Double expandFactor) throws StorageException {
        long userId = getUserId();
        MapInitialResponse cached = mapInitialCache.get(userId);
        if (cached != null) {
            LOGGER.debug("API /positions/map: userId={} from cache", userId);
            return Response.ok(cached).build();
        }
        MapBoundsRow bounds = storage.getMapBoundsForUser(userId);
        if (bounds == null || bounds.getDeviceCount() == 0) {
            MapInitialResponse empty = new MapInitialResponse();
            empty.setMinLat(-85);
            empty.setMaxLat(85);
            empty.setMinLon(-180);
            empty.setMaxLon(180);
            empty.setZoom(2);
            empty.setDeviceCount(0);
            empty.setPositions(List.of());
            empty.setClusters(List.of());
            return Response.ok(empty).build();
        }
        double pad = 1.1;
        double lonSpan = Math.max((bounds.getMaxLon() - bounds.getMinLon()) * pad, 0.01);
        double latSpan = Math.max((bounds.getMaxLat() - bounds.getMinLat()) * pad, 0.01);
        int zoom = (int) Math.floor(Math.min(
                Math.log(360.0 / lonSpan) / Math.log(2),
                Math.log(180.0 / latSpan) / Math.log(2)));
        zoom = clampZoom(zoom);
        double minLat = bounds.getMinLat();
        double maxLat = bounds.getMaxLat();
        double minLon = bounds.getMinLon();
        double maxLon = bounds.getMaxLon();
        double factor = expandFactor != null ? Math.max(0, Math.min(2, expandFactor)) : DEFAULT_EXPAND_FACTOR;
        Bounds expanded = expandBounds(minLat, maxLat, minLon, maxLon, factor);
        // Precomputed cache: band 0 = most zoomed out (500km eps). Use it for fast initial load when available.
        List<MapCellRow> cells = storage.getMapClustersFromCache(
                userId, 0, expanded.minLat(), expanded.maxLat(), expanded.minLon(), expanded.maxLon());
        if (cells.isEmpty()) {
            // Cache miss or not populated: fall back to live clustering (zoom 0 → 2000 km eps from table).
            double epsMeters = epsMetersForMap(0);
            cells = storage.getMapCellsInBoundsDistance(userId, expanded.minLat(), expanded.maxLat(), expanded.minLon(), expanded.maxLon(), epsMeters);
        }
        // Initial load: clusters only (no single positions) so markers group instead of overlap.
        boolean includeSinglePoints = false;
        PositionsMapResponse plot = mapCellsToResponse(cells, includeSinglePoints);
        MapInitialResponse response = new MapInitialResponse();
        response.setMinLat(expanded.minLat());
        response.setMaxLat(expanded.maxLat());
        response.setMinLon(expanded.minLon());
        response.setMaxLon(expanded.maxLon());
        response.setZoom(zoom);
        response.setDeviceCount((int) bounds.getDeviceCount());
        response.setPositions(plot.getPositions());
        response.setClusters(plot.getClusters());
        mapInitialCache.put(userId, response);
        LOGGER.info("API /positions/map: userId={} -> bounds [{},{},{},{}] zoom={} deviceCount={} positions={} clusters={}",
                userId, expanded.minLat(), expanded.maxLat(), expanded.minLon(), expanded.maxLon(), zoom, response.getDeviceCount(),
                plot.getPositions().size(), plot.getClusters().size());
        return Response.ok(response).build();
    }

    @Path("{id}")
    @DELETE
    public Response removeById(@PathParam("id") long positionId) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getReadonly);

        Request request = new Request(new Columns.All(), new Condition.Equals("id", positionId));
        Position position = storage.getObject(Position.class, request);
        if (position == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        permissionsService.checkPermission(Device.class, getUserId(), position.getDeviceId());

        storage.removeObject(Position.class, request);
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    @DELETE
    public Response remove(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") Date from, @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getReadonly);

        var conditions = new LinkedList<Condition>();
        conditions.add(new Condition.Equals("deviceId", deviceId));
        conditions.add(new Condition.Between("fixTime", from, to));
        storage.removeObject(Position.class, new Request(Condition.merge(conditions)));

        return Response.status(Response.Status.NO_CONTENT).build();
    }

    @Path("kml")
    @GET
    @Produces("application/vnd.google-earth.kml+xml")
    public Response getKml(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") Date from, @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        StreamingOutput stream = output -> {
            try {
                kmlExportProvider.generate(output, deviceId, from, to);
            } catch (StorageException e) {
                throw new WebApplicationException(e);
            }
        };
        return Response.ok(stream)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=positions.kml").build();
    }

    @Path("csv")
    @GET
    @Produces("text/csv")
    public Response getCsv(
            @QueryParam("deviceId") long deviceId, @QueryParam("geofenceId") long geofenceId,
            @QueryParam("from") Date from, @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        StreamingOutput stream = output -> {
            try {
                csvExportProvider.generate(output, getUserId(), deviceId, geofenceId, from, to);
            } catch (StorageException e) {
                throw new WebApplicationException(e);
            }
        };
        return Response.ok(stream)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=positions.csv").build();
    }

    @Path("gpx")
    @GET
    @Produces("application/gpx+xml")
    public Response getGpx(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") Date from, @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        StreamingOutput stream = output -> {
            try {
                gpxExportProvider.generate(output, deviceId, from, to);
            } catch (StorageException e) {
                throw new WebApplicationException(e);
            }
        };
        return Response.ok(stream)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=positions.gpx").build();
    }

}
