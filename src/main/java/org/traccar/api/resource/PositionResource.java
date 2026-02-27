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

    private static final int CLUSTER_PIXEL_SIZE = 40;

    /** Max cell size in degrees (~220 km at equator). Avoids one giant cluster at low zoom. */
    private static final double MAX_CELL_DEGREES = 2.0;
    /** Min cell size in degrees (~1 km). Avoids excessive cluster count at high zoom. */
    private static final double MIN_CELL_DEGREES = 0.01;

    /** From zoom 16 onward do not cluster: return each position individually. */
    private static final int NO_CLUSTER_ZOOM_THRESHOLD = 16;

    /** Zoom level from longitude span (narrow bounds => higher zoom => smaller cells). */
    private static int zoomFromLonSpan(double lonSpan) {
        if (lonSpan <= 0) return 22;
        int z = (int) Math.floor(Math.log(360.0 / Math.max(lonSpan, 0.001)) / Math.log(2));
        return Math.max(0, Math.min(22, z));
    }

    /** Grid cell size in degrees for clustering. Capped so low zoom gets more clusters, high zoom doesn't explode. */
    private static double cellDegForZoom(int zoom) {
        double raw = (360.0 / (256 * (1 << Math.max(0, Math.min(zoom, 22))))) * CLUSTER_PIXEL_SIZE;
        return Math.max(MIN_CELL_DEGREES, Math.min(MAX_CELL_DEGREES, raw));
    }

    /** Approximate earth circumference in meters (for distance-based clustering). */
    private static final double EARTH_CIRCUMFERENCE_M = 40_075_017.0;

    /** Eps in meters for DBSCAN at given zoom (≈ CLUSTER_PIXEL_SIZE pixels at that zoom). */
    private static double epsMetersForZoom(int zoom) {
        int z = Math.max(0, Math.min(22, zoom));
        return CLUSTER_PIXEL_SIZE * EARTH_CIRCUMFERENCE_M / (256.0 * (1 << z));
    }

    /** Min/max cluster radius (meters). Bounds-derived max keeps distribution across viewport. */
    private static final double MIN_EPS_METERS = 400.0;
    private static final double MAX_EPS_METERS = 8000.0;

    /**
     * Max cluster radius from viewport size so we get multiple clusters across the map, not one giant cluster.
     * Uses shorter span of bounds (in meters); target ~15–20 clusters along that axis.
     */
    private static double maxEpsFromBounds(double minLat, double maxLat, double minLon, double maxLon) {
        double latSpanDeg = Math.max(0.001, maxLat - minLat);
        double lonSpanDeg = Math.max(0.001, maxLon - minLon);
        double midLat = (minLat + maxLat) * 0.5;
        double latMeters = 111_320.0 * latSpanDeg;
        double lonMeters = 111_320.0 * Math.cos(Math.toRadians(midLat)) * lonSpanDeg;
        double spanMeters = Math.min(latMeters, lonMeters);
        double maxEps = spanMeters / 18.0;
        return Math.max(MIN_EPS_METERS, Math.min(MAX_EPS_METERS, maxEps));
    }

    /** Effective eps: zoom-based value capped by bounds-derived max (and global min/max). */
    private static double epsMetersForMap(int zoom, double minLat, double maxLat, double minLon, double maxLon) {
        double fromZoom = epsMetersForZoom(zoom);
        double maxFromBounds = maxEpsFromBounds(minLat, maxLat, minLon, maxLon);
        double eps = Math.min(fromZoom, maxFromBounds);
        return Math.max(MIN_EPS_METERS, Math.min(MAX_EPS_METERS, eps));
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
            int effectiveZoom = (zoom != null && zoom >= 0)
                ? Math.max(0, Math.min(22, zoom))
                : zoomFromBounds;
            List<MapCellRow> cells;
            if (storage.hasPostGIS()) {
                double epsMeters = epsMetersForMap(effectiveZoom, expanded.minLat(), expanded.maxLat(), expanded.minLon(), expanded.maxLon());
                cells = storage.getMapCellsInBoundsDistance(userId, expanded.minLat(), expanded.maxLat(), expanded.minLon(), expanded.maxLon(), epsMeters);
            } else {
                double cellDeg = cellDegForZoom(effectiveZoom);
                cells = storage.getMapCellsInBounds(userId, expanded.minLat(), expanded.maxLat(), expanded.minLon(), expanded.maxLon(), cellDeg);
            }
            LOGGER.info("API /positions map-view: userId={} bounds=[{},{},{},{}] expanded -> {} cells from DB",
                    userId, expanded.minLat(), expanded.maxLat(), expanded.minLon(), expanded.maxLon(), cells.size());
            PositionsMapResponse mapResponse = mapCellsToResponse(cells);
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

    /** Converts DB cluster rows (one per cell) to positions list + clusters list. No grouping in memory. */
    private static PositionsMapResponse mapCellsToResponse(List<MapCellRow> cells) {
        var positions = new ArrayList<PositionMapItem>();
        var clusters = new ArrayList<PositionCluster>();
        for (var row : cells) {
            if (row.getCount() == 1) {
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
        zoom = Math.max(0, Math.min(20, zoom));
        double minLat = bounds.getMinLat();
        double maxLat = bounds.getMaxLat();
        double minLon = bounds.getMinLon();
        double maxLon = bounds.getMaxLon();
        double factor = expandFactor != null ? Math.max(0, Math.min(2, expandFactor)) : DEFAULT_EXPAND_FACTOR;
        Bounds expanded = expandBounds(minLat, maxLat, minLon, maxLon, factor);
        int zoomForClustering = zoom >= NO_CLUSTER_ZOOM_THRESHOLD ? 22 : zoom;
        List<MapCellRow> cells;
        if (storage.hasPostGIS()) {
            double epsMeters = epsMetersForMap(zoomForClustering, expanded.minLat(), expanded.maxLat(), expanded.minLon(), expanded.maxLon());
            cells = storage.getMapCellsInBoundsDistance(userId, expanded.minLat(), expanded.maxLat(), expanded.minLon(), expanded.maxLon(), epsMeters);
        } else {
            double cellDeg = cellDegForZoom(zoomForClustering);
            cells = storage.getMapCellsInBounds(userId, expanded.minLat(), expanded.maxLat(), expanded.minLon(), expanded.maxLon(), cellDeg);
        }
        PositionsMapResponse plot = mapCellsToResponse(cells);
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
