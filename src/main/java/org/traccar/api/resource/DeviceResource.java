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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.core.Context;
import org.traccar.api.BaseObjectResource;
import org.traccar.api.signature.TokenManager;
import org.traccar.broadcast.BroadcastService;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.MediaManager;
import org.traccar.helper.LogAction;
import org.traccar.model.Device;
import org.traccar.model.DeviceAccumulators;
import org.traccar.model.DeviceStatusCounts;
import org.traccar.model.Page;
import org.traccar.model.Permission;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Path("devices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DeviceResource extends BaseObjectResource<Device> {

    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private static final int IMAGE_SIZE_LIMIT = 500000;

    @Inject
    private Config config;

    @Inject
    private CacheManager cacheManager;

    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private BroadcastService broadcastService;

    @Inject
    private MediaManager mediaManager;

    @Inject
    private TokenManager tokenManager;

    @Inject
    private LogAction actionLogger;

    @Context
    private HttpServletRequest request;

    public DeviceResource() {
        super(Device.class);
    }

    private Stream<Device> getFilteredStream(
            Columns columns, List<Condition> conditions, String search) throws StorageException {
        Stream<Device> stream = storage.getObjectsStream(baseClass, new Request(
                columns, Condition.merge(conditions), new Order("name")));
        if (search != null && !search.isEmpty()) {
            String searchLower = search.toLowerCase();
            return stream.filter(device ->
                (device.getName() != null && device.getName().toLowerCase().contains(searchLower)) ||
                (device.getUniqueId() != null && device.getUniqueId().toLowerCase().contains(searchLower)));
        }
        return stream;
    }

    @GET
    public Response get(
            @QueryParam("all") boolean all, @QueryParam("userId") long userId,
            @QueryParam("uniqueId") List<String> uniqueIds,
            @QueryParam("id") List<Long> deviceIds,
            @QueryParam("excludeAttributes") boolean excludeAttributes,
            @QueryParam("offset") int offset,
            @QueryParam("limit") int limit,
            @QueryParam("search") String search,
            @QueryParam("groupId") Long groupId,
            @QueryParam("status") String status,
            @QueryParam("sortBy") String sortBy,
            @QueryParam("sortOrder") String sortOrder,
            @QueryParam("lastUpdateFrom") Date lastUpdateFrom,
            @QueryParam("lastUpdateTo") Date lastUpdateTo) throws StorageException {

        Columns columns = excludeAttributes ? new Columns.Exclude("attributes") : new Columns.All();

        if (!uniqueIds.isEmpty() || !deviceIds.isEmpty()) {

            List<Device> result = new LinkedList<>();
            for (String uniqueId : uniqueIds) {
                result.addAll(storage.getObjects(Device.class, new Request(
                        columns,
                        new Condition.And(
                                new Condition.Equals("uniqueId", uniqueId),
                                new Condition.Permission(User.class, getUserId(), Device.class)))));
            }
            for (Long deviceId : deviceIds) {
                result.addAll(storage.getObjects(Device.class, new Request(
                        columns,
                        new Condition.And(
                                new Condition.Equals("id", deviceId),
                                new Condition.Permission(User.class, getUserId(), Device.class)))));
            }
            return Response.ok(result.stream()).build();

        } else {

            // Check if new filters are used
            boolean useNewFilters = groupId != null || status != null || 
                                   lastUpdateFrom != null || lastUpdateTo != null || 
                                   (sortBy != null && !sortBy.isEmpty());

            if (useNewFilters) {
                // NEW CODE PATH: Use getDevicesWithFilters() for DB-level filtering
                
                // Validate status
                if (status != null && !status.isEmpty()) {
                    String statusLower = status.toLowerCase();
                    if (!statusLower.equals("online") && !statusLower.equals("offline") && 
                        !statusLower.equals("unknown") && !statusLower.equals("nr")) {
                        return Response.status(Response.Status.BAD_REQUEST)
                            .entity("Invalid status. Must be: online, offline, unknown, or NR")
                            .build();
                    }
                }
                
                // Validate sortBy
                if (sortBy != null && !sortBy.isEmpty()) {
                    String sortByLower = sortBy.toLowerCase();
                    if (!sortByLower.equals("name") && !sortByLower.equals("uniqueid") && 
                        !sortByLower.equals("groupid") && !sortByLower.equals("status") && 
                        !sortByLower.equals("lastupdate")) {
                        return Response.status(Response.Status.BAD_REQUEST)
                            .entity("Invalid sortBy. Must be: name, uniqueId, groupId, status, or lastUpdate")
                            .build();
                    }
                }
                
                // Parse sortOrder
                boolean sortDescending = "desc".equalsIgnoreCase(sortOrder);
                
                // Determine effective userId and whether to skip permission filter
                long effectiveUserId = userId;
                boolean skipPermissionFilter = false;
                if (all) {
                    if (permissionsService.notAdmin(getUserId())) {
                        effectiveUserId = getUserId();
                    } else {
                        // Admin can see all devices when all=true
                        skipPermissionFilter = true;
                        effectiveUserId = getUserId(); // Still needed for potential future use
                    }
                } else {
                    if (userId == 0) {
                        effectiveUserId = getUserId();
                    } else {
                        permissionsService.checkUser(getUserId(), userId);
                        effectiveUserId = userId;
                    }
                }
                
                // Get total count for pagination
                long totalElements = 0;
                if (offset > 0 || limit > 0) {
                    try (Stream<Device> countStream = storage.getDevicesWithFilters(
                        effectiveUserId, columns, skipPermissionFilter, groupId, status, search,
                        lastUpdateFrom, lastUpdateTo, sortBy, sortDescending, 0, 0)) {
                        totalElements = countStream.count();
                    }
                }
                
                // Get filtered devices
                List<Device> content;
                try (Stream<Device> stream = storage.getDevicesWithFilters(
                    effectiveUserId, columns, skipPermissionFilter, groupId, status, search,
                    lastUpdateFrom, lastUpdateTo, sortBy, sortDescending, offset, limit)) {
                    content = stream.toList();
                }
                
                if (offset > 0 || limit > 0) {
                    Page<Device> page = new Page<>(content, totalElements, offset, limit);
                    // Global status counts from DB. Prefer getDeviceStatusCounts (Postgres); else count from same DB stream as existing path.
                    DeviceStatusCounts statusCounts = storage.getDeviceStatusCounts(
                            effectiveUserId, skipPermissionFilter, null, null, null, null);
                    if (statusCounts != null) {
                        page.setTotalOnline(statusCounts.online());
                        page.setTotalOffline(statusCounts.offline());
                        page.setTotalUnknown(statusCounts.unknown());
                    } else {
                        var conditions = new LinkedList<Condition>();
                        if (!skipPermissionFilter) {
                            conditions.add(new Condition.Permission(User.class, effectiveUserId, baseClass));
                        }
                        try (Stream<Device> stream = getFilteredStream(columns, conditions, null)) {
                            Map<String, Long> m = stream.collect(Collectors.groupingBy(
                                    d -> d.getStatus() != null ? d.getStatus().toLowerCase() : Device.STATUS_OFFLINE,
                                    Collectors.counting()));
                            long on = m.getOrDefault(Device.STATUS_ONLINE, 0L);
                            long off = m.getOrDefault(Device.STATUS_OFFLINE, 0L);
                            long tot = m.values().stream().mapToLong(Long::longValue).sum();
                            page.setTotalOnline(on);
                            page.setTotalOffline(off);
                            page.setTotalUnknown(tot - on - off);
                        }
                    }
                    return Response.ok(page).build();
                } else {
                    return Response.ok(content).build();
                }
                
            } else {
                // EXISTING CODE PATH: Backward compatible
                var conditions = new LinkedList<Condition>();

                if (all) {
                    if (permissionsService.notAdmin(getUserId())) {
                        conditions.add(new Condition.Permission(User.class, getUserId(), baseClass));
                    }
                } else {
                    if (userId == 0) {
                        conditions.add(new Condition.Permission(User.class, getUserId(), baseClass));
                    } else {
                        permissionsService.checkUser(getUserId(), userId);
                        conditions.add(new Condition.Permission(User.class, userId, baseClass).excludeGroups());
                    }
                }

                if (offset > 0 || limit > 0) {
                    Map<String, Long> statusCountsMap;
                    long totalElements;
                    try (Stream<Device> stream = getFilteredStream(columns, conditions, search)) {
                        statusCountsMap = stream.collect(Collectors.groupingBy(
                                d -> d.getStatus() != null ? d.getStatus().toLowerCase() : Device.STATUS_OFFLINE,
                                Collectors.counting()));
                    }
                    totalElements = statusCountsMap.values().stream().mapToLong(Long::longValue).sum();
                    List<Device> content;
                    try (Stream<Device> stream = getFilteredStream(columns, conditions, search)) {
                        content = stream
                                .skip(offset)
                                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                                .collect(Collectors.toList());
                    }
                    Page<Device> page = new Page<>(content, totalElements, offset, limit);
                    long online = statusCountsMap.getOrDefault(Device.STATUS_ONLINE, 0L);
                    long offline = statusCountsMap.getOrDefault(Device.STATUS_OFFLINE, 0L);
                    page.setTotalOnline(online);
                    page.setTotalOffline(offline);
                    page.setTotalUnknown(totalElements - online - offline);
                    return Response.ok(page).build();
                } else {
                    return Response.ok(getFilteredStream(columns, conditions, search)).build();
                }
            }

        }
    }

    @Path("{id}/accumulators")
    @PUT
    public Response updateAccumulators(DeviceAccumulators entity) throws Exception {
        permissionsService.checkPermission(Device.class, getUserId(), entity.getDeviceId());
        permissionsService.checkEdit(getUserId(), Device.class, false, false);

        Position position = storage.getObject(Position.class, new Request(
                new Columns.All(), new Condition.LatestPositions(entity.getDeviceId())));
        if (position != null) {
            if (entity.getTotalDistance() != null) {
                position.getAttributes().put(Position.KEY_TOTAL_DISTANCE, entity.getTotalDistance());
            }
            if (entity.getHours() != null) {
                position.getAttributes().put(Position.KEY_HOURS, entity.getHours());
            }
            position.setId(storage.addObject(position, new Request(new Columns.Exclude("id"))));

            Device device = new Device();
            device.setId(position.getDeviceId());
            device.setPositionId(position.getId());
            storage.updateObject(device, new Request(
                    new Columns.Include("positionId"),
                    new Condition.Equals("id", device.getId())));

            var key = new Object();
            try {
                cacheManager.addDevice(position.getDeviceId(), key);
                cacheManager.updatePosition(position);
                connectionManager.updatePosition(true, position);
            } finally {
                cacheManager.removeDevice(position.getDeviceId(), key);
            }
        } else {
            throw new IllegalArgumentException();
        }

        actionLogger.resetAccumulators(request, getUserId(), entity.getDeviceId());
        return Response.noContent().build();
    }

    private String imageExtension(String type) {
        return switch (type) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            default -> throw new IllegalArgumentException("Unsupported image type");
        };
    }

    @Path("{id}/image")
    @POST
    @Consumes("image/*")
    public Response uploadImage(
            @PathParam("id") long deviceId, File file,
            @HeaderParam(HttpHeaders.CONTENT_TYPE) String type) throws StorageException, IOException {

        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("id", deviceId),
                        new Condition.Permission(User.class, getUserId(), Device.class))));
        if (device != null) {
            String name = "device";
            String extension = imageExtension(type);
            try (var input = new FileInputStream(file);
                    var output = mediaManager.createFileStream(device.getUniqueId(), name, extension)) {

                long transferred = 0;
                byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer, 0, buffer.length)) >= 0) {
                    output.write(buffer, 0, read);
                    transferred += read;
                    if (transferred > IMAGE_SIZE_LIMIT) {
                        throw new IllegalArgumentException("Image size limit exceeded");
                    }
                }
            }
            return Response.ok(name + "." + extension).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @Path("share")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @POST
    public String shareDevice(
            @FormParam("deviceId") long deviceId,
            @FormParam("expiration") Date expiration) throws StorageException, GeneralSecurityException, IOException {

        User user = permissionsService.getUser(getUserId());
        if (permissionsService.getServer().getBoolean(Keys.DEVICE_SHARE_DISABLE.getKey())) {
            throw new SecurityException("Sharing is disabled");
        }
        if (user.getTemporary()) {
            throw new SecurityException("Temporary user");
        }
        if (user.getExpirationTime() != null && user.getExpirationTime().before(expiration)) {
            expiration = user.getExpirationTime();
        }

        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("id", deviceId),
                        new Condition.Permission(User.class, user.getId(), Device.class))));

        String shareEmail = user.getEmail() + ":" + device.getUniqueId();
        User share = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("email", shareEmail)));

        if (share == null) {
            share = new User();
            share.setName(device.getName());
            share.setEmail(shareEmail);
            share.setExpirationTime(expiration);
            share.setTemporary(true);
            share.setReadonly(true);
            share.setLimitCommands(user.getLimitCommands() || !config.getBoolean(Keys.WEB_SHARE_DEVICE_COMMANDS));
            share.setDisableReports(user.getDisableReports() || !config.getBoolean(Keys.WEB_SHARE_DEVICE_REPORTS));

            share.setId(storage.addObject(share, new Request(new Columns.Exclude("id"))));

            storage.addPermission(new Permission(User.class, share.getId(), Device.class, deviceId));
        }

        return tokenManager.generateToken(share.getId(), expiration);
    }

}
