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

    private static final int CLUSTER_PIXEL_SIZE = 40;

    @GET
    public Response get(
            @QueryParam("deviceId") long deviceId, @QueryParam("id") List<Long> positionIds,
            @QueryParam("geofenceId") long geofenceId, @QueryParam("from") Date from, @QueryParam("to") Date to,
            @QueryParam("minLat") Double minLat, @QueryParam("maxLat") Double maxLat,
            @QueryParam("minLon") Double minLon, @QueryParam("maxLon") Double maxLon,
            @QueryParam("zoom") Integer zoom)
            throws StorageException {
        boolean mapView = minLat != null && maxLat != null && minLon != null && maxLon != null && zoom != null
                && positionIds.isEmpty() && deviceId <= 0 && from == null && to == null;
        if (mapView) {
            long userId = getUserId();
            double cellDeg = (360.0 / (256 * (1 << Math.max(0, Math.min(zoom, 22))))) * CLUSTER_PIXEL_SIZE;
            var cells = storage.getMapCellsInBounds(userId, minLat, maxLat, minLon, maxLon, cellDeg);
            LOGGER.info("API /positions map-view: userId={} bounds=[{},{},{},{}] zoom={} -> {} cells from DB",
                    userId, minLat, maxLat, minLon, maxLon, zoom, cells.size());
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

                Stream<Position> stream = PositionUtil.getPositionsStream(storage, deviceId, from, to)
                        .filter(position -> geofence == null || geofence.containsPosition(position));
                return Response.ok(stream).build();
            } else {
                String turbo = permissionsService.getServer().getString("position.turbo", "24 hours");
                Stream<Position> stream = storage.getObjectsStream(Position.class, new Request(
                        new Columns.All(), new Condition.LatestPositions(deviceId, 0, turbo)));
                return Response.ok(stream).build();
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
                item.setName(row.getName());
                item.setStatus(row.getStatus());
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
    public Response getMapInitial() throws StorageException {
        long userId = getUserId();
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
        double cellDeg = (360.0 / (256 * (1 << Math.max(0, Math.min(zoom, 22))))) * CLUSTER_PIXEL_SIZE;
        var cells = storage.getMapCellsInBounds(userId, minLat, maxLat, minLon, maxLon, cellDeg);
        PositionsMapResponse plot = mapCellsToResponse(cells);
        MapInitialResponse response = new MapInitialResponse();
        response.setMinLat(minLat);
        response.setMaxLat(maxLat);
        response.setMinLon(minLon);
        response.setMaxLon(maxLon);
        response.setZoom(zoom);
        response.setDeviceCount((int) bounds.getDeviceCount());
        response.setPositions(plot.getPositions());
        response.setClusters(plot.getClusters());
        LOGGER.info("API /positions/map: userId={} -> bounds [{},{},{},{}] zoom={} deviceCount={} positions={} clusters={}",
                userId, minLat, maxLat, minLon, maxLon, zoom, response.getDeviceCount(),
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
