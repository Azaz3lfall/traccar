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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.traccar.config.Config;
import org.traccar.model.BaseModel;
import org.traccar.model.Device;
import org.traccar.model.MapBoundsRow;
import org.traccar.model.MapCellRow;
import org.traccar.model.Position;
import org.traccar.model.PositionMapItem;
import org.traccar.model.PositionWithDevice;
import org.traccar.model.User;
import org.traccar.model.Group;
import org.traccar.model.GroupedModel;
import org.traccar.model.Permission;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DatabaseStorage extends Storage {

    private final Config config;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String databaseType;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(DatabaseStorage.class);

    @Inject
    public DatabaseStorage(Config config, DataSource dataSource, ObjectMapper objectMapper) {
        this.config = config;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;

        try (var connection = dataSource.getConnection()) {
            databaseType = connection.getMetaData().getDatabaseProductName();
            LOGGER.info("Connected to {} database", databaseType);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> List<T> getObjects(Class<T> clazz, Request request) throws StorageException {
        try (var objects = getObjectsStream(clazz, request)) {
            return objects.toList();
        }
    }

    @Override
    public <T> Stream<T> getObjectsStream(Class<T> clazz, Request request) throws StorageException {
        StringBuilder query = new StringBuilder("SELECT ");
        if (request.getColumns() instanceof Columns.All) {
            query.append('*');
        } else {
            query.append(formatColumns(request.getColumns().getColumns(clazz, "set"), c -> c));
        }
        query.append(" FROM ").append(getStorageName(clazz));
        query.append(formatCondition(request.getCondition()));
        query.append(formatOrder(request.getOrder()));
        try {
            QueryBuilder builder = QueryBuilder.create(config, dataSource, objectMapper, query.toString());
            List<Object> values = getConditionVariables(request.getCondition());
            for (int index = 0; index < values.size(); index++) {
                builder.setValue(index, values.get(index));
            }
            return builder.executeQueryStreamed(clazz);
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    @Override
    public <T> long addObject(T entity, Request request) throws StorageException {
        List<String> columns = request.getColumns().getColumns(entity.getClass(), "get");
        StringBuilder query = new StringBuilder("INSERT INTO ");
        query.append(getStorageName(entity.getClass()));
        query.append("(");
        query.append(formatColumns(columns, c -> c));
        query.append(") VALUES (");
        query.append(formatColumns(columns, c -> "?"));
        query.append(")");
        try {
            QueryBuilder builder = QueryBuilder.create(config, dataSource, objectMapper, query.toString(), true);
            builder.setObject(entity, columns);
            return builder.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    @Override
    public <T> void updateObject(T entity, Request request) throws StorageException {
        List<String> columns = request.getColumns().getColumns(entity.getClass(), "get");
        StringBuilder query = new StringBuilder("UPDATE ");
        query.append(getStorageName(entity.getClass()));
        query.append(" SET ");
        query.append(formatColumns(columns, c -> c + " = ?"));
        query.append(formatCondition(request.getCondition()));
        try {
            QueryBuilder builder = QueryBuilder.create(config, dataSource, objectMapper, query.toString());
            builder.setObject(entity, columns);
            List<Object> values = getConditionVariables(request.getCondition());
            for (int index = 0; index < values.size(); index++) {
                builder.setValue(columns.size() + index, values.get(index));
            }
            builder.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    @Override
    public void removeObject(Class<?> clazz, Request request) throws StorageException {
        StringBuilder query = new StringBuilder("DELETE FROM ");
        query.append(getStorageName(clazz));
        query.append(formatCondition(request.getCondition()));
        try {
            QueryBuilder builder = QueryBuilder.create(config, dataSource, objectMapper, query.toString());
            List<Object> values = getConditionVariables(request.getCondition());
            for (int index = 0; index < values.size(); index++) {
                builder.setValue(index, values.get(index));
            }
            builder.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    @Override
    public List<Permission> getPermissions(
            Class<? extends BaseModel> ownerClass, long ownerId,
            Class<? extends BaseModel> propertyClass, long propertyId) throws StorageException {
        StringBuilder query = new StringBuilder("SELECT * FROM ");
        query.append(Permission.getStorageName(ownerClass, propertyClass));
        var conditions = new LinkedList<Condition>();
        if (ownerId > 0) {
            conditions.add(new Condition.Equals(Permission.getKey(ownerClass), ownerId));
        }
        if (propertyId > 0) {
            conditions.add(new Condition.Equals(Permission.getKey(propertyClass), propertyId));
        }
        Condition combinedCondition = Condition.merge(conditions);
        query.append(formatCondition(combinedCondition));
        try {
            QueryBuilder builder = QueryBuilder.create(config, dataSource, objectMapper, query.toString());
            List<Object> values = getConditionVariables(combinedCondition);
            for (int index = 0; index < values.size(); index++) {
                builder.setValue(index, values.get(index));
            }
            return builder.executePermissionsQuery();
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    @Override
    public void addPermission(Permission permission) throws StorageException {
        var entries = permission.get().entrySet().stream().toList();
        StringBuilder query = new StringBuilder("INSERT INTO ");
        query.append(permission.getStorageName());
        query.append(" VALUES (");
        query.append(entries.stream().map(e -> "?").collect(Collectors.joining(", ")));
        query.append(")");
        try {
            QueryBuilder builder = QueryBuilder.create(config, dataSource, objectMapper, query.toString(), true);
            for (int index = 0; index < entries.size(); index++) {
                builder.setLong(index, entries.get(index).getValue());
            }
            builder.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    @Override
    public void removePermission(Permission permission) throws StorageException {
        var entries = permission.get().entrySet().stream().toList();
        StringBuilder query = new StringBuilder("DELETE FROM ");
        query.append(permission.getStorageName());
        query.append(" WHERE ");
        query.append(entries.stream().map(e -> e.getKey() + " = ?").collect(Collectors.joining(" AND ")));
        try {
            QueryBuilder builder = QueryBuilder.create(config, dataSource, objectMapper, query.toString(), true);
            for (int index = 0; index < entries.size(); index++) {
                builder.setLong(index, entries.get(index).getValue());
            }
            builder.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    @Override
    public List<PositionWithDevice> getPositionsInBoundsWithDevice(
            long userId, double minLat, double maxLat, double minLon, double maxLon) throws StorageException {
        if (!databaseType.toLowerCase().contains("postgresql")) {
            LOGGER.debug("getPositionsInBoundsWithDevice: skipped (not PostgreSQL)");
            return List.of();
        }
        try {
            // Single query: bounds filter + permission subquery (no 12k params). Same permission as web.
            String posTable = getStorageName(Position.class);
            String devTable = getStorageName(Device.class);
            String permittedDevices = buildPermittedDeviceIdsSubquery();
            String sql = "SELECT p.*, d.name AS name, COALESCE(d.status, 'offline') AS status "
                    + "FROM ("
                    + "  SELECT DISTINCT ON (deviceid) * FROM " + posTable
                    + "  WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?"
                    + "  AND deviceid IN (" + permittedDevices + ")"
                    + "  ORDER BY deviceid, fixtime DESC"
                    + ") p "
                    + "INNER JOIN " + devTable + " d ON d.id = p.deviceid";
            var builder = QueryBuilder.create(config, dataSource, objectMapper, sql);
            builder.setDouble(0, minLat);
            builder.setDouble(1, maxLat);
            builder.setDouble(2, minLon);
            builder.setDouble(3, maxLon);
            builder.setLong(4, userId);
            builder.setLong(5, userId);
            try (var stream = builder.executeQueryStreamed(PositionWithDevice.class)) {
                var list = stream.toList();
                LOGGER.debug("getPositionsInBoundsWithDevice: user {} -> {} positions in bounds", userId, list.size());
                return list;
            }
        } catch (SQLException e) {
            LOGGER.warn("getPositionsInBoundsWithDevice: query failed", e);
            throw new StorageException(e);
        }
    }

    @Override
    public List<PositionMapItem> getPositionsInBoundsForMapView(
            long userId, double minLat, double maxLat, double minLon, double maxLon) throws StorageException {
        if (!databaseType.toLowerCase().contains("postgresql")) {
            LOGGER.debug("getPositionsInBoundsForMapView: skipped (not PostgreSQL)");
            return List.of();
        }
        try {
            String posTable = getStorageName(Position.class);
            String devTable = getStorageName(Device.class);
            String permittedDevices = buildPermittedDeviceIdsSubquery();
            String sql = "SELECT p.id, p.deviceid, p.latitude, p.longitude, d.name AS name, COALESCE(d.status, 'offline') AS status "
                    + "FROM ("
                    + "  SELECT DISTINCT ON (deviceid) id, deviceid, latitude, longitude FROM " + posTable
                    + "  WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?"
                    + "  AND deviceid IN (" + permittedDevices + ")"
                    + "  ORDER BY deviceid, fixtime DESC"
                    + ") p "
                    + "INNER JOIN " + devTable + " d ON d.id = p.deviceid";
            var builder = QueryBuilder.create(config, dataSource, objectMapper, sql);
            builder.setDouble(0, minLat);
            builder.setDouble(1, maxLat);
            builder.setDouble(2, minLon);
            builder.setDouble(3, maxLon);
            builder.setLong(4, userId);
            builder.setLong(5, userId);
            try (var stream = builder.executeQueryStreamed(PositionMapItem.class)) {
                var list = stream.toList();
                LOGGER.debug("getPositionsInBoundsForMapView: user {} -> {} positions in bounds", userId, list.size());
                return list;
            }
        } catch (SQLException e) {
            LOGGER.warn("getPositionsInBoundsForMapView: query failed", e);
            throw new StorageException(e);
        }
    }

    @Override
    public List<MapCellRow> getMapCellsInBounds(
            long userId, double minLat, double maxLat, double minLon, double maxLon, double cellDeg) throws StorageException {
        if (!databaseType.toLowerCase().contains("postgresql")) {
            LOGGER.debug("getMapCellsInBounds: skipped (not PostgreSQL)");
            return List.of();
        }
        try {
            String posTable = getStorageName(Position.class);
            String devTable = getStorageName(Device.class);
            String permittedDevices = buildPermittedDeviceIdsSubquery();
            String sql = "WITH latest AS ("
                    + "  SELECT p.id, p.deviceid, p.latitude, p.longitude, p.course, d.name AS name, COALESCE(d.status, 'offline') AS status, d.category AS category "
                    + "  FROM ("
                    + "    SELECT DISTINCT ON (deviceid) id, deviceid, latitude, longitude, course FROM " + posTable
                    + "    WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?"
                    + "    AND deviceid IN (" + permittedDevices + ")"
                    + "    ORDER BY deviceid, fixtime DESC"
                    + "  ) p INNER JOIN " + devTable + " d ON d.id = p.deviceid"
                    + "),"
                    + " with_cell AS ("
                    + "  SELECT *, FLOOR(longitude / ?)::bigint AS cell_x, FLOOR(latitude / ?)::bigint AS cell_y FROM latest"
                    + ")"
                    + " SELECT COUNT(*) AS count, AVG(latitude) AS latitude, AVG(longitude) AS longitude,"
                    + " MIN(id) AS id, MIN(deviceid) AS deviceid, MIN(course) AS course, MIN(name) AS name, MIN(status) AS status, MIN(category) AS category"
                    + " FROM with_cell GROUP BY cell_x, cell_y";
            var builder = QueryBuilder.create(config, dataSource, objectMapper, sql);
            builder.setDouble(0, minLat);
            builder.setDouble(1, maxLat);
            builder.setDouble(2, minLon);
            builder.setDouble(3, maxLon);
            builder.setLong(4, userId);
            builder.setLong(5, userId);
            builder.setDouble(6, cellDeg);
            builder.setDouble(7, cellDeg);
            try (var stream = builder.executeQueryStreamed(MapCellRow.class)) {
                var list = stream.toList();
                LOGGER.debug("getMapCellsInBounds: user {} -> {} cells from DB", userId, list.size());
                return list;
            }
        } catch (SQLException e) {
            LOGGER.warn("getMapCellsInBounds: query failed", e);
            throw new StorageException(e);
        }
    }

    @Override
    public boolean hasPostGIS() throws StorageException {
        if (!databaseType.toLowerCase().contains("postgresql")) {
            return false;
        }
        try {
            String sql = "SELECT 1 FROM pg_extension WHERE extname = 'postgis'";
            try (var conn = dataSource.getConnection();
                 var st = conn.prepareStatement(sql);
                 var rs = st.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOGGER.debug("hasPostGIS: check failed", e);
            return false;
        }
    }

    /** Approximate meters per degree at equator for WGS84 (used to convert eps meters → degrees). */
    private static final double METERS_PER_DEGREE = 111_320.0;

    @Override
    public List<MapCellRow> getMapCellsInBoundsDistance(
            long userId, double minLat, double maxLat, double minLon, double maxLon, double epsMeters) throws StorageException {
        if (!databaseType.toLowerCase().contains("postgresql")) {
            LOGGER.debug("getMapCellsInBoundsDistance: skipped (not PostgreSQL)");
            return List.of();
        }
        try {
            if (!hasPostGIS()) {
                LOGGER.debug("getMapCellsInBoundsDistance: skipped (PostGIS not available)");
                return List.of();
            }
            String posTable = getStorageName(Position.class);
            String devTable = getStorageName(Device.class);
            String permittedDevices = buildPermittedDeviceIdsSubquery();
            double epsDegrees = epsMeters / METERS_PER_DEGREE;
            String sql = "WITH latest AS ("
                    + "  SELECT p.id, p.deviceid, p.latitude, p.longitude, p.course, d.name AS name, COALESCE(d.status, 'offline') AS status, d.category AS category "
                    + "  FROM ("
                    + "    SELECT DISTINCT ON (deviceid) id, deviceid, latitude, longitude, course FROM " + posTable
                    + "    WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?"
                    + "    AND deviceid IN (" + permittedDevices + ")"
                    + "    ORDER BY deviceid, fixtime DESC"
                    + "  ) p INNER JOIN " + devTable + " d ON d.id = p.deviceid"
                    + "),"
                    + " with_geom AS ("
                    + "  SELECT *, ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geometry AS geom FROM latest"
                    + "),"
                    + " with_cluster AS ("
                    + "  SELECT *, COALESCE("
                    + "    ST_ClusterDBSCAN(geom, ?::double precision, 1) OVER (ORDER BY id),"
                    + "    -ROW_NUMBER() OVER (ORDER BY id)"
                    + "  ) AS cluster_id FROM with_geom"
                    + ")"
                    + " SELECT COUNT(*) AS count, AVG(latitude) AS latitude, AVG(longitude) AS longitude,"
                    + " MIN(id) AS id, MIN(deviceid) AS deviceid, MIN(course) AS course, MIN(name) AS name, MIN(status) AS status, MIN(category) AS category"
                    + " FROM with_cluster GROUP BY cluster_id";
            var builder = QueryBuilder.create(config, dataSource, objectMapper, sql);
            builder.setDouble(0, minLat);
            builder.setDouble(1, maxLat);
            builder.setDouble(2, minLon);
            builder.setDouble(3, maxLon);
            builder.setLong(4, userId);
            builder.setLong(5, userId);
            builder.setDouble(6, epsDegrees);
            try (var stream = builder.executeQueryStreamed(MapCellRow.class)) {
                var list = stream.toList();
                LOGGER.debug("getMapCellsInBoundsDistance: user {} -> {} clusters from DB", userId, list.size());
                return list;
            }
        } catch (SQLException e) {
            LOGGER.warn("getMapCellsInBoundsDistance: query failed", e);
            throw new StorageException(e);
        }
    }

    @Override
    public Stream<Device> getDevicesWithFilters(
            long userId, Columns columns, boolean skipPermissionFilter, Long groupId, String status, String search,
            Date lastUpdateFrom, Date lastUpdateTo, String sortBy, boolean sortDescending,
            int offset, int limit) throws StorageException {
        if (!databaseType.toLowerCase().contains("postgresql")) {
            LOGGER.debug("getDevicesWithFilters: skipped (not PostgreSQL)");
            return Stream.empty();
        }
        try {
            String devTable = getStorageName(Device.class);
            
            // Build SELECT clause
            StringBuilder sql = new StringBuilder("SELECT ");
            if (columns instanceof Columns.All) {
                sql.append("d.*");
            } else {
                List<String> columnList = columns.getColumns(Device.class, "set");
                if (columnList.isEmpty()) {
                    sql.append("d.*");
                } else {
                    sql.append(formatColumns(columnList, c -> "d." + c));
                }
            }
            sql.append(" FROM ").append(devTable).append(" d");
            
            List<Object> params = new ArrayList<>();
            int paramIndex = 0;
            
            // Permission filter (skip if admin + all=true)
            if (!skipPermissionFilter) {
                String permittedDevices = buildPermittedDeviceIdsSubquery();
                sql.append(" WHERE d.id IN (").append(permittedDevices).append(")");
                // Permission params (userId x2)
                params.add(userId);
                params.add(userId);
                paramIndex += 2;
            } else {
                sql.append(" WHERE 1=1");
            }
            
            // groupId filter
            if (groupId != null) {
                sql.append(" AND d.groupid = ?");
                params.add(groupId);
                paramIndex++;
            }
            
            // status filter
            boolean isNrStatus = status != null && !status.isEmpty() && "nr".equalsIgnoreCase(status);
            if (status != null && !status.isEmpty() && !isNrStatus) {
                sql.append(" AND COALESCE(d.status, 'offline') = ?");
                params.add(status.toLowerCase());
                paramIndex++;
            }
            
            // lastUpdate filters (skip if status="NR")
            if (!isNrStatus) {
                if (lastUpdateFrom != null) {
                    sql.append(" AND d.lastupdate >= ?");
                    params.add(new Timestamp(lastUpdateFrom.getTime()));
                    paramIndex++;
                }
                if (lastUpdateTo != null) {
                    sql.append(" AND d.lastupdate <= ?");
                    params.add(new Timestamp(lastUpdateTo.getTime()));
                    paramIndex++;
                }
            }
            
            // "NR" status filter (null lastUpdate)
            if (isNrStatus) {
                sql.append(" AND d.lastupdate IS NULL");
            }
            
            // search filter (name + uniqueId)
            if (search != null && !search.isEmpty()) {
                String searchPattern = "%" + search + "%";
                sql.append(" AND (LOWER(d.name) LIKE LOWER(?) OR LOWER(d.uniqueid) LIKE LOWER(?))");
                params.add(searchPattern);
                params.add(searchPattern);
                paramIndex += 2;
            }
            
            // ORDER BY
            sql.append(" ORDER BY ");
            if (sortBy != null && !sortBy.isEmpty()) {
                String sortByLower = sortBy.toLowerCase();
                switch (sortByLower) {
                    case "name":
                        sql.append("d.name");
                        break;
                    case "uniqueid":
                        sql.append("d.uniqueid");
                        break;
                    case "groupid":
                        sql.append("d.groupid");
                        break;
                    case "status":
                        sql.append("COALESCE(d.status, 'offline')");
                        break;
                    case "lastupdate":
                        sql.append("d.lastupdate");
                        break;
                    default:
                        sql.append("d.name");
                }
                sql.append(sortDescending ? " DESC" : " ASC");
                if ("lastupdate".equals(sortByLower)) {
                    sql.append(sortDescending ? " NULLS FIRST" : " NULLS LAST");
                }
            } else {
                sql.append("d.name ASC");
            }
            
            // LIMIT/OFFSET
            if (limit > 0) {
                sql.append(" LIMIT ? OFFSET ?");
                params.add(limit);
                params.add(offset);
            }
            
            var builder = QueryBuilder.create(config, dataSource, objectMapper, sql.toString());
            for (int i = 0; i < params.size(); i++) {
                builder.setValue(i, params.get(i));
            }
            
            return builder.executeQueryStreamed(Device.class);
        } catch (SQLException e) {
            LOGGER.warn("getDevicesWithFilters: query failed", e);
            throw new StorageException(e);
        }
    }

    @Override
    public MapBoundsRow getMapBoundsForUser(long userId) throws StorageException {
        if (!databaseType.toLowerCase().contains("postgresql")) {
            return null;
        }
        try {
            String posTable = getStorageName(Position.class);
            String permittedDevices = buildPermittedDeviceIdsSubquery();
            String sql = "SELECT MIN(latitude) AS \"minLat\", MAX(latitude) AS \"maxLat\", "
                    + "MIN(longitude) AS \"minLon\", MAX(longitude) AS \"maxLon\", COUNT(*) AS \"deviceCount\" "
                    + "FROM ("
                    + "  SELECT DISTINCT ON (deviceid) latitude, longitude FROM " + posTable
                    + "  WHERE deviceid IN (" + permittedDevices + ")"
                    + "  ORDER BY deviceid, fixtime DESC"
                    + ") sub";
            var builder = QueryBuilder.create(config, dataSource, objectMapper, sql);
            builder.setLong(0, userId);
            builder.setLong(1, userId);
            try (var stream = builder.executeQueryStreamed(MapBoundsRow.class)) {
                MapBoundsRow row = stream.findFirst().orElse(null);
                if (row != null) {
                    LOGGER.debug("getMapBoundsForUser: user {} -> bounds [{},{},{},{}] count {}",
                            userId, row.getMinLat(), row.getMaxLat(), row.getMinLon(), row.getMaxLon(), row.getDeviceCount());
                }
                return row;
            }
        } catch (SQLException e) {
            LOGGER.warn("getMapBoundsForUser: query failed", e);
            throw new StorageException(e);
        }
    }

    /** Same device list as web: tc_user_device + devices from user's groups (with hierarchy). 2 params: userId, userId. */
    private String buildPermittedDeviceIdsSubquery() throws StorageException {
        String groupTable = getStorageName(Group.class);
        String userDeviceTable = Permission.getStorageName(User.class, Device.class);
        String userGroupTable = Permission.getStorageName(User.class, Group.class);
        return "SELECT " + userDeviceTable + ".deviceid FROM " + userDeviceTable + " WHERE userid = ? "
                + "UNION "
                + "SELECT DISTINCT devices.deviceid FROM " + userGroupTable
                + " INNER JOIN ("
                + "  SELECT id AS parentid, id AS groupid FROM " + groupTable
                + "  UNION SELECT groupid AS parentid, id AS groupid FROM " + groupTable + " WHERE groupid IS NOT NULL"
                + "  UNION SELECT g2.groupid AS parentid, g1.id AS groupid FROM " + groupTable + " AS g2"
                + "  INNER JOIN " + groupTable + " AS g1 ON g2.id = g1.groupid WHERE g2.groupid IS NOT NULL"
                + ") AS all_groups ON " + userGroupTable + ".groupid = all_groups.parentid "
                + "INNER JOIN (SELECT groupid AS parentid, id AS deviceid FROM " + getStorageName(Device.class)
                + " WHERE groupid IS NOT NULL) AS devices ON all_groups.groupid = devices.parentid "
                + "WHERE " + userGroupTable + ".userid = ?";
    }

    private String getStorageName(Class<?> clazz) throws StorageException {
        StorageName storageName = clazz.getAnnotation(StorageName.class);
        if (storageName == null) {
            throw new StorageException("StorageName annotation is missing");
        }
        return storageName.value();
    }

    private List<Object> getConditionVariables(Condition genericCondition) {
        List<Object> results = new ArrayList<>();
        if (genericCondition instanceof Condition.Compare condition) {
            results.add(condition.getValue());
        } else if (genericCondition instanceof Condition.Between condition) {
            results.add(condition.getFromValue());
            results.add(condition.getToValue());
        } else if (genericCondition instanceof Condition.Binary condition) {
            results.addAll(getConditionVariables(condition.getFirst()));
            results.addAll(getConditionVariables(condition.getSecond()));
        } else if (genericCondition instanceof Condition.Permission condition) {
            long conditionId = condition.getOwnerId() > 0 ? condition.getOwnerId() : condition.getPropertyId();
            results.add(conditionId);
            if (condition.getIncludeGroups()) {
                results.add(conditionId);
            }
        } else if (genericCondition instanceof Condition.LatestPositions condition) {
            if (condition.getUserId() > 0) {
                results.add(condition.getUserId());
            }
            if (condition.getTurbo() != null) {
                results.add(condition.getTurbo());
            }
            if (condition.getDeviceId() > 0) {
                results.add(condition.getDeviceId());
            }
        }
        return results;
    }

    private String formatColumns(List<String> columns, Function<String, String> mapper) {
        return columns.stream().map(mapper).collect(Collectors.joining(", "));
    }

    private String formatCondition(Condition genericCondition) throws StorageException {
        return formatCondition(genericCondition, true);
    }

    private String formatCondition(Condition genericCondition, boolean appendWhere) throws StorageException {
        StringBuilder result = new StringBuilder();
        if (genericCondition != null) {
            if (appendWhere) {
                result.append(" WHERE ");
            }
            if (genericCondition instanceof Condition.Compare condition) {

                result.append(condition.getColumn());
                result.append(" ");
                result.append(condition.getOperator());
                result.append(" ?");

            } else if (genericCondition instanceof Condition.Between condition) {

                result.append(condition.getColumn());
                result.append(" BETWEEN ? AND ?");

            } else if (genericCondition instanceof Condition.Binary condition) {

                if (genericCondition instanceof Condition.Or) {
                    result.append('(');
                }
                result.append(formatCondition(condition.getFirst(), false));
                result.append(" ");
                result.append(condition.getOperator());
                result.append(" ");
                result.append(formatCondition(condition.getSecond(), false));
                if (genericCondition instanceof Condition.Or) {
                    result.append(')');
                }

            } else if (genericCondition instanceof Condition.Permission condition) {

                result.append("id IN (");
                result.append(formatPermissionQuery(condition));
                result.append(")");

            } else if (genericCondition instanceof Condition.LatestPositions condition) {

                if (databaseType.toLowerCase().contains("postgresql")) {
                    LOGGER.info("Generating optimized PostgreSQL query for LatestPositions. Window: {}", condition.getTurbo());
                    result.append("id IN (");
                    result.append("SELECT d.positionid FROM ").append(getStorageName(Device.class)).append(" d ");
                    result.append("JOIN tc_user_device ud ON d.id = ud.deviceid AND ud.userid = ? ");
                    result.append("JOIN ").append(getStorageName(Position.class)).append(" p ON d.positionid = p.id ");
                    result.append("WHERE 1=1 ");
                    if (condition.getTurbo() != null) {
                        result.append("AND p.fixtime > NOW() - (?)::interval ");
                    }
                    if (condition.getDeviceId() > 0) {
                        result.append("AND d.id = ? ");
                    }
                    result.append(")");
                } else {
                    LOGGER.warn("Using SLOW fallback query for LatestPositions. DB type: {}", databaseType);
                    result.append("id IN (");
                    result.append("SELECT positionId FROM ");
                    result.append(getStorageName(Device.class));
                    if (condition.getDeviceId() > 0) {
                        result.append(" WHERE id = ?");
                    }
                    result.append(")");
                }

            }
        }
        return result.toString();
    }

    private String formatOrder(Order order) {
        StringBuilder result = new StringBuilder();
        if (order != null) {
            result.append(" ORDER BY ");
            result.append(order.getColumn());
            if (order.getDescending()) {
                result.append(" DESC");
            }
            if (order.getLimit() > 0) {
                if (databaseType.equals("Microsoft SQL Server")) {
                    result.append(" OFFSET 0 ROWS FETCH FIRST ");
                    result.append(order.getLimit());
                    result.append(" ROWS ONLY");
                } else {
                    result.append(" LIMIT ");
                    result.append(order.getLimit());
                }
            }
        }
        return result.toString();
    }

    private String formatPermissionQuery(Condition.Permission condition) throws StorageException {
        StringBuilder result = new StringBuilder();

        String outputKey;
        String conditionKey;
        if (condition.getOwnerId() > 0) {
            outputKey = Permission.getKey(condition.getPropertyClass());
            conditionKey = Permission.getKey(condition.getOwnerClass());
        } else {
            outputKey = Permission.getKey(condition.getOwnerClass());
            conditionKey = Permission.getKey(condition.getPropertyClass());
        }

        String storageName = Permission.getStorageName(condition.getOwnerClass(), condition.getPropertyClass());
        result.append("SELECT ");
        result.append(storageName).append('.').append(outputKey);
        result.append(" FROM ");
        result.append(storageName);
        result.append(" WHERE ");
        result.append(conditionKey);
        result.append(" = ?");

        if (condition.getIncludeGroups()) {

            boolean expandDevices;
            String groupStorageName;
            if (GroupedModel.class.isAssignableFrom(condition.getOwnerClass())) {
                expandDevices = Device.class.isAssignableFrom(condition.getOwnerClass());
                groupStorageName = Permission.getStorageName(Group.class, condition.getPropertyClass());
            } else {
                expandDevices = Device.class.isAssignableFrom(condition.getPropertyClass());
                groupStorageName = Permission.getStorageName(condition.getOwnerClass(), Group.class);
            }

            result.append(" UNION ");

            result.append("SELECT DISTINCT ");
            if (!expandDevices) {
                if (outputKey.equals("groupId")) {
                    result.append("all_groups.");
                } else {
                    result.append(groupStorageName).append('.');
                }
            }
            result.append(outputKey);
            result.append(" FROM ");
            result.append(groupStorageName);

            result.append(" INNER JOIN (");
            result.append("SELECT id as parentId, id as groupId FROM ");
            result.append(getStorageName(Group.class));
            result.append(" UNION ");
            result.append("SELECT groupId as parentId, id as groupId FROM ");
            result.append(getStorageName(Group.class));
            result.append(" WHERE groupId IS NOT NULL");
            result.append(" UNION ");
            result.append("SELECT g2.groupId as parentId, g1.id as groupId FROM ");
            result.append(getStorageName(Group.class));
            result.append(" AS g2");
            result.append(" INNER JOIN ");
            result.append(getStorageName(Group.class));
            result.append(" AS g1 ON g2.id = g1.groupId");
            result.append(" WHERE g2.groupId IS NOT NULL");
            result.append(") AS all_groups ON ");
            result.append(groupStorageName);
            result.append(".groupId = all_groups.parentId");

            if (expandDevices) {
                result.append(" INNER JOIN (");
                result.append("SELECT groupId as parentId, id as deviceId FROM ");
                result.append(getStorageName(Device.class));
                result.append(" WHERE groupId IS NOT NULL");
                result.append(") AS devices ON all_groups.groupId = devices.parentId");
            }

            result.append(" WHERE ");
            result.append(conditionKey);
            result.append(" = ?");

        }

        return result.toString();
    }

}
