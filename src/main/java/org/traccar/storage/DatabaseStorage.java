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

    /** Time window for map/position queries (e.g. "24 hours", "2 days"). Uses position.turbo from config. */
    private String getPositionTurboWindow() {
        String value = config.getString("position.turbo");
        return value != null ? value : "24 hours";
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
            String posTable = getStorageName(Position.class);
            String devTable = getStorageName(Device.class);
            String permittedDevices = buildPermittedDeviceIdsSubquery();
            String sql = "SELECT p.*, d.name AS name, COALESCE(d.status, 'offline') AS status "
                    + "FROM " + devTable + " d "
                    + "INNER JOIN " + posTable + " p ON d.positionid = p.id "
                    + "WHERE p.latitude BETWEEN ? AND ? AND p.longitude BETWEEN ? AND ?"
                    + "  AND d.id IN (" + permittedDevices + ")";
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
            StringBuilder sql = new StringBuilder()
                    .append("SELECT p.id, p.deviceid, p.latitude, p.longitude, p.course, d.name AS name, ")
                    .append("COALESCE(d.status, 'offline') AS status, d.category AS category ")
                    .append("FROM ").append(devTable).append(" d ")
                    .append("INNER JOIN ").append(posTable).append(" p ON d.positionid = p.id ")
                    .append("WHERE p.latitude BETWEEN ? AND ? AND p.longitude BETWEEN ? AND ? ")
                    .append("AND d.id IN (").append(permittedDevices).append(")");
            var builder = QueryBuilder.create(config, dataSource, objectMapper, sql.toString());
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
            long userId,
            double minLat,
            double maxLat,
            double minLon,
            double maxLon,
            double cellDeg) throws StorageException {
        if (!databaseType.toLowerCase().contains("postgresql")) {
            LOGGER.debug("getMapCellsInBounds: skipped (not PostgreSQL)");
            return List.of();
        }
        try {
            String posTable = getStorageName(Position.class);
            String devTable = getStorageName(Device.class);
            String permittedDevices = buildPermittedDeviceIdsSubquery();
            StringBuilder sql = new StringBuilder()
                    .append("WITH latest AS (")
                    .append("  SELECT p.id, p.deviceid, p.latitude, p.longitude, p.course, d.name AS name, ")
                    .append("COALESCE(d.status, 'offline') AS status, d.category AS category ")
                    .append("  FROM ").append(devTable).append(" d ")
                    .append("  INNER JOIN ").append(posTable).append(" p ON d.positionid = p.id ")
                    .append("  WHERE p.latitude BETWEEN ? AND ? AND p.longitude BETWEEN ? AND ? ")
                    .append("    AND d.id IN (").append(permittedDevices).append(")")
                    .append("), with_cell AS (")
                    .append("  SELECT *, FLOOR(longitude / ?)::bigint AS cell_x, ")
                    .append("FLOOR(latitude / ?)::bigint AS cell_y FROM latest")
                    .append(") SELECT COUNT(*) AS count, AVG(latitude) AS latitude, AVG(longitude) AS longitude, ")
                    .append("MIN(id) AS id, MIN(deviceid) AS deviceid, MIN(course) AS course, MIN(name) AS name, ")
                    .append("MIN(status) AS status, MIN(category) AS category ")
                    .append("FROM with_cell GROUP BY cell_x, cell_y");
            var builder = QueryBuilder.create(config, dataSource, objectMapper, sql.toString());
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

    /**
     * Approximate meters per degree at equator for WGS84 (used to convert eps meters → degrees).
     */
    private static final double METERS_PER_DEGREE = 111_320.0;

    @Override
    public List<MapCellRow> getMapCellsInBoundsDistance(
            long userId,
            double minLat,
            double maxLat,
            double minLon,
            double maxLon,
            double epsMeters) throws StorageException {
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
            StringBuilder sql = new StringBuilder()
                    .append("WITH latest AS (")
                    .append("  SELECT p.id, p.deviceid, p.latitude, p.longitude, p.course, d.name AS name, ")
                    .append("COALESCE(d.status, 'offline') AS status, d.category AS category ")
                    .append("  FROM ").append(devTable).append(" d ")
                    .append("  INNER JOIN ").append(posTable).append(" p ON d.positionid = p.id ")
                    .append("  WHERE p.latitude BETWEEN ? AND ? AND p.longitude BETWEEN ? AND ? ")
                    .append("    AND d.id IN (").append(permittedDevices).append(")")
                    .append("), with_geom AS (")
                    .append("  SELECT *, ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geometry AS geom ")
                    .append("FROM latest")
                    .append("), with_cluster AS (")
                    .append("  SELECT *, COALESCE(")
                    .append("    ST_ClusterDBSCAN(geom, ?::double precision, 5) OVER (ORDER BY id),")
                    .append("    -ROW_NUMBER() OVER (ORDER BY id)")
                    .append("  ) AS cluster_id FROM with_geom")
                    .append(") SELECT COUNT(*) AS count, AVG(latitude) AS latitude, AVG(longitude) AS longitude, ")
                    .append("MIN(id) AS id, MIN(deviceid) AS deviceid, MIN(course) AS course, MIN(name) AS name, ")
                    .append("MIN(status) AS status, MIN(category) AS category ")
                    .append("FROM with_cluster GROUP BY cluster_id");
            var builder = QueryBuilder.create(config, dataSource, objectMapper, sql.toString());
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

    private static final String MAP_CLUSTERS_TABLE = "tc_map_clusters";

    /** Eps in meters per zoom band: 0=500km .. 8=1km (9 bands). Used for reference; task defines actual values. */
    private static final int MAP_CLUSTER_ZOOM_BANDS = 9;

    @Override
    public List<MapCellRow> getMapClustersForUser(long userId, double epsMeters) throws StorageException {
        if (!databaseType.toLowerCase().contains("postgresql")) {
            return List.of();
        }
        try {
            if (!hasPostGIS()) {
                return List.of();
            }
            String posTable = getStorageName(Position.class);
            String devTable = getStorageName(Device.class);
            String permittedDevices = buildPermittedDeviceIdsSubquery();
            double epsDegrees = epsMeters / METERS_PER_DEGREE;
            StringBuilder sql = new StringBuilder()
                    .append("WITH latest AS (")
                    .append("  SELECT p.id, p.deviceid, p.latitude, p.longitude, p.course, d.name AS name, ")
                    .append("COALESCE(d.status, 'offline') AS status, d.category AS category ")
                    .append("  FROM ").append(devTable).append(" d ")
                    .append("  INNER JOIN ").append(posTable).append(" p ON d.positionid = p.id ")
                    .append("  WHERE d.id IN (").append(permittedDevices).append(")")
                    .append("), with_geom AS (")
                    .append("  SELECT *, ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geometry AS geom ")
                    .append("FROM latest")
                    .append("), with_cluster AS (")
                    .append("  SELECT *, COALESCE(")
                    .append("    ST_ClusterDBSCAN(geom, ?::double precision, 5) OVER (ORDER BY id),")
                    .append("    -ROW_NUMBER() OVER (ORDER BY id)")
                    .append("  ) AS cluster_id FROM with_geom")
                    .append(") SELECT COUNT(*) AS count, AVG(latitude) AS latitude, AVG(longitude) AS longitude, ")
                    .append("MIN(id) AS id, MIN(deviceid) AS deviceid, MIN(course) AS course, MIN(name) AS name, ")
                    .append("MIN(status) AS status, MIN(category) AS category ")
                    .append("FROM with_cluster GROUP BY cluster_id");
            var builder = QueryBuilder.create(config, dataSource, objectMapper, sql.toString());
            builder.setLong(0, userId);
            builder.setLong(1, userId);
            builder.setDouble(2, epsDegrees);
            try (var stream = builder.executeQueryStreamed(MapCellRow.class)) {
                return stream.toList();
            }
        } catch (SQLException e) {
            LOGGER.warn("getMapClustersForUser: query failed", e);
            throw new StorageException(e);
        }
    }

    @Override
    public void saveMapClusters(long userId, int zoomBand, List<MapCellRow> clusters) throws StorageException {
        if (!databaseType.toLowerCase().contains("postgresql")) {
            return;
        }
        try {
            String deleteSql = "DELETE FROM " + MAP_CLUSTERS_TABLE + " WHERE userid = ? AND zoom_band = ?";
            var deleteBuilder = QueryBuilder.create(config, dataSource, objectMapper, deleteSql);
            deleteBuilder.setLong(0, userId);
            deleteBuilder.setInteger(1, zoomBand);
            deleteBuilder.executeUpdate();
            if (clusters.isEmpty()) {
                return;
            }
            String insertSql = new StringBuilder()
                    .append("INSERT INTO ").append(MAP_CLUSTERS_TABLE)
                    .append(" (userid, zoom_band, latitude, longitude, count, position_id, deviceid, course, ")
                    .append("name, status, category)")
                    .append(" VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .toString();
            for (var row : clusters) {
                var insertBuilder = QueryBuilder.create(config, dataSource, objectMapper, insertSql);
                int i = 0;
                insertBuilder.setLong(i++, userId);
                insertBuilder.setInteger(i++, zoomBand);
                insertBuilder.setDouble(i++, row.getLatitude());
                insertBuilder.setDouble(i++, row.getLongitude());
                insertBuilder.setLong(i++, row.getCount());
                insertBuilder.setLong(i++, row.getCount() == 1 ? row.getId() : 0, true);
                insertBuilder.setLong(i++, row.getCount() == 1 ? row.getDeviceId() : 0, true);
                insertBuilder.setDouble(i++, row.getCourse());
                insertBuilder.setString(i++, row.getName());
                insertBuilder.setString(i++, row.getStatus());
                insertBuilder.setString(i++, row.getCategory());
                insertBuilder.executeUpdate();
            }
            LOGGER.debug("saveMapClusters: user {} zoom_band {} -> {} clusters", userId, zoomBand, clusters.size());
        } catch (SQLException e) {
            LOGGER.warn("saveMapClusters failed", e);
            throw new StorageException(e);
        }
    }

    @Override
    public List<MapCellRow> getMapClustersFromCache(
            long userId, int zoomBand, double minLat, double maxLat, double minLon, double maxLon)
            throws StorageException {
        if (!databaseType.toLowerCase().contains("postgresql")) {
            return List.of();
        }
        try {
            String sql = new StringBuilder()
                    .append("SELECT latitude, longitude, count, position_id AS \"id\", deviceid AS \"deviceId\", ")
                    .append("course, name, status, category ")
                    .append("FROM ").append(MAP_CLUSTERS_TABLE).append(" ")
                    .append("WHERE userid = ? AND zoom_band = ? AND latitude BETWEEN ? AND ? ")
                    .append("AND longitude BETWEEN ? AND ?")
                    .toString();
            var builder = QueryBuilder.create(config, dataSource, objectMapper, sql);
            builder.setLong(0, userId);
            builder.setInteger(1, zoomBand);
            builder.setDouble(2, minLat);
            builder.setDouble(3, maxLat);
            builder.setDouble(4, minLon);
            builder.setDouble(5, maxLon);
            try (var stream = builder.executeQueryStreamed(MapCellRow.class)) {
                return stream.toList();
            }
        } catch (SQLException e) {
            LOGGER.warn("getMapClustersFromCache failed", e);
            return List.of();
        }
    }

    @Override
    public MapBoundsRow getMapBoundsForUser(long userId) throws StorageException {
        if (!databaseType.toLowerCase().contains("postgresql")) {
            return null;
        }
        try {
            String posTable = getStorageName(Position.class);
            String devTable = getStorageName(Device.class);
            String permittedDevices = buildPermittedDeviceIdsSubquery();
            String sql = new StringBuilder()
                    .append("SELECT MIN(p.latitude) AS \"minLat\", MAX(p.latitude) AS \"maxLat\", ")
                    .append("MIN(p.longitude) AS \"minLon\", MAX(p.longitude) AS \"maxLon\", ")
                    .append("COUNT(*) AS \"deviceCount\" ")
                    .append("FROM ").append(devTable).append(" d ")
                    .append("INNER JOIN ").append(posTable).append(" p ON d.positionid = p.id ")
                    .append("WHERE d.id IN (").append(permittedDevices).append(")")
                    .toString();
            var builder = QueryBuilder.create(config, dataSource, objectMapper, sql);
            builder.setLong(0, userId);
            builder.setLong(1, userId);
            try (var stream = builder.executeQueryStreamed(MapBoundsRow.class)) {
                MapBoundsRow row = stream.findFirst().orElse(null);
                if (row != null) {
                    LOGGER.debug("getMapBoundsForUser: user {} -> bounds [{},{},{},{}] count {}",
                            userId,
                            row.getMinLat(), row.getMaxLat(), row.getMinLon(), row.getMaxLon(),
                            row.getDeviceCount());
                }
                return row;
            }
        } catch (SQLException e) {
            LOGGER.warn("getMapBoundsForUser: query failed", e);
            throw new StorageException(e);
        }
    }

    /**
     * Same device list as web: tc_user_device + devices from user's groups (with hierarchy).
     * 2 params: userId, userId.
     */
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
            if (condition.getDeviceId() > 0) {
                results.add(condition.getDeviceId());
                results.add(condition.getDeviceId());
            } else {
                long period = config.getLong(org.traccar.config.Keys.DATABASE_POSITION_PERIOD);
                if (period > 0) {
                    results.add(new Date(System.currentTimeMillis() - period * 1000));
                }
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

                if (condition.getDeviceId() > 0) {
                    result.append("deviceId = ? AND ");
                } else {
                    long period = config.getLong(org.traccar.config.Keys.DATABASE_POSITION_PERIOD);
                    if (period > 0) {
                        result.append("fixTime > ? AND ");
                    }
                }
                result.append("id IN (");
                result.append("SELECT positionId FROM ");
                result.append(getStorageName(Device.class));
                if (condition.getDeviceId() > 0) {
                    result.append(" WHERE id = ?");
                }
                result.append(")");

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
