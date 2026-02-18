# Device API Filters Implementation Plan v2 - Raw SQL / DB Level

## Requirements

- **All operations at DB level** (PostgreSQL raw SQL)
- **Full backward compatibility** (existing API unchanged)
- **Single groupId** (not multiple groups)
- **Status filter** with "NR" (Never Reported) option for null lastUpdate
- **Sort by** all fields including status and lastUpdate (DB level)
- **Search** already works (name + uniqueId) - keep as is

## Implementation Approach

Create a **new method** in `DatabaseStorage` that builds raw SQL for device queries with all filters. This keeps backward compatibility - existing `get()` method in `DeviceResource` continues to work, and we add a new code path for filtered queries.

## Architecture

### Option A: New Storage Method (Recommended)

Add `getDevicesWithFilters()` method to `Storage` interface and `DatabaseStorage` implementation. This method builds custom SQL with all filters.

**Pros:**
- Clean separation
- Backward compatible (existing code path unchanged)
- Can optimize SQL for PostgreSQL specifically

**Cons:**
- More code duplication (but acceptable for performance)

### Option B: Extend Existing getObjectsStream

Modify `getObjectsStream` to detect special filter parameters and build custom SQL.

**Pros:**
- Single code path

**Cons:**
- More complex, harder to maintain backward compatibility
- Risk of breaking existing queries

**Decision: Option A** - New method `getDevicesWithFilters()` in Storage/DatabaseStorage

---

## New Query Parameters

Add to `DeviceResource.get()` method:

```java
@QueryParam("groupId") Long groupId,                    // Filter by single group
@QueryParam("status") String status,                   // "online", "offline", "unknown", "NR" (Never Reported = null lastUpdate)
@QueryParam("sortBy") String sortBy,                   // "name", "uniqueId", "groupId", "status", "lastUpdate"
@QueryParam("sortOrder") String sortOrder,             // "asc" or "desc" (default: "asc")
@QueryParam("lastUpdateFrom") Date lastUpdateFrom,     // Filter devices updated >= this date
@QueryParam("lastUpdateTo") Date lastUpdateTo          // Filter devices updated <= this date
```

**Note:** Keep existing `search` parameter (already searches name + uniqueId).

---

## SQL Query Structure

### Base Query

```sql
SELECT d.*
FROM tc_devices d
WHERE d.id IN (
    -- Permission subquery (same as existing)
    SELECT deviceid FROM tc_user_device WHERE userid = ?
    UNION
    SELECT DISTINCT devices.deviceid FROM tc_user_group ...
)
```

### Filter Clauses (added to WHERE)

1. **groupId filter:**
   ```sql
   AND d.groupid = ?
   ```

2. **status filter:**
   ```sql
   -- For "online", "offline", "unknown":
   AND COALESCE(d.status, 'offline') = ?
   
   -- For "NR" (Never Reported):
   AND d.lastupdate IS NULL
   ```

3. **lastUpdateFrom filter:**
   ```sql
   AND d.lastupdate >= ?
   ```

4. **lastUpdateTo filter:**
   ```sql
   AND d.lastupdate <= ?
   ```

5. **search filter (name + uniqueId):**
   ```sql
   AND (
       LOWER(d.name) LIKE LOWER(?) OR
       LOWER(d.uniqueid) LIKE LOWER(?)
   )
   -- Where ? = '%searchTerm%'
   ```

### Sort Clause

```sql
ORDER BY
  CASE WHEN ? = 'name' THEN d.name END ASC,
  CASE WHEN ? = 'name' AND ? = 'desc' THEN d.name END DESC,
  CASE WHEN ? = 'uniqueId' THEN d.uniqueid END ASC,
  CASE WHEN ? = 'uniqueId' AND ? = 'desc' THEN d.uniqueid END DESC,
  CASE WHEN ? = 'groupId' THEN d.groupid END ASC,
  CASE WHEN ? = 'groupId' AND ? = 'desc' THEN d.groupid END DESC,
  CASE WHEN ? = 'status' THEN COALESCE(d.status, 'offline') END ASC,
  CASE WHEN ? = 'status' AND ? = 'desc' THEN COALESCE(d.status, 'offline') END DESC,
  CASE WHEN ? = 'lastUpdate' THEN d.lastupdate END ASC NULLS LAST,
  CASE WHEN ? = 'lastUpdate' AND ? = 'desc' THEN d.lastupdate END DESC NULLS FIRST
```

**Better approach:** Use dynamic ORDER BY based on sortBy parameter:

```sql
ORDER BY 
  CASE 
    WHEN ? = 'name' AND ? = 'asc' THEN d.name
    WHEN ? = 'name' AND ? = 'desc' THEN d.name
    WHEN ? = 'uniqueId' AND ? = 'asc' THEN d.uniqueid
    WHEN ? = 'uniqueId' AND ? = 'desc' THEN d.uniqueid
    WHEN ? = 'groupId' AND ? = 'asc' THEN d.groupid
    WHEN ? = 'groupId' AND ? = 'desc' THEN d.groupid
    WHEN ? = 'status' AND ? = 'asc' THEN COALESCE(d.status, 'offline')
    WHEN ? = 'status' AND ? = 'desc' THEN COALESCE(d.status, 'offline')
    WHEN ? = 'lastUpdate' AND ? = 'asc' THEN d.lastupdate
    WHEN ? = 'lastUpdate' AND ? = 'desc' THEN d.lastupdate
  END
  ASC/DESC NULLS LAST/FIRST
```

**Simplest:** Build ORDER BY clause dynamically in Java:

```sql
ORDER BY 
  CASE ? 
    WHEN 'name' THEN d.name
    WHEN 'uniqueId' THEN d.uniqueid
    WHEN 'groupId' THEN CAST(d.groupid AS TEXT)
    WHEN 'status' THEN COALESCE(d.status, 'offline')
    WHEN 'lastUpdate' THEN CAST(d.lastupdate AS TEXT)
  END
  ASC/DESC
```

**Best:** Use PostgreSQL's dynamic ordering with CASE and proper NULL handling:

```sql
ORDER BY 
  CASE 
    WHEN ? = 'name' THEN d.name
    WHEN ? = 'uniqueId' THEN d.uniqueid
    WHEN ? = 'groupId' THEN d.groupid::text
    WHEN ? = 'status' THEN COALESCE(d.status, 'offline')
    WHEN ? = 'lastUpdate' THEN d.lastupdate::text
    ELSE d.name
  END
  ASC/DESC
  NULLS LAST  -- for lastUpdate, nulls go last when ASC, first when DESC
```

**Recommended:** Build ORDER BY string dynamically in Java based on sortBy/sortOrder:

```java
String orderBy;
if ("lastUpdate".equals(sortBy)) {
    orderBy = "d.lastupdate " + (descending ? "DESC NULLS FIRST" : "ASC NULLS LAST");
} else if ("status".equals(sortBy)) {
    orderBy = "COALESCE(d.status, 'offline') " + (descending ? "DESC" : "ASC");
} else if ("name".equals(sortBy)) {
    orderBy = "d.name " + (descending ? "DESC" : "ASC");
} else if ("uniqueId".equals(sortBy)) {
    orderBy = "d.uniqueid " + (descending ? "DESC" : "ASC");
} else if ("groupId".equals(sortBy)) {
    orderBy = "d.groupid " + (descending ? "DESC" : "ASC");
} else {
    orderBy = "d.name ASC";  // default
}
```

### Pagination

```sql
LIMIT ? OFFSET ?
```

---

## Implementation Steps

### 1. Add Method to Storage Interface

```java
// In Storage.java
public <T> Stream<T> getDevicesWithFilters(
    long userId,
    Columns columns,
    Long groupId,
    String status,
    String search,
    Date lastUpdateFrom,
    Date lastUpdateTo,
    String sortBy,
    boolean sortDescending,
    int offset,
    int limit) throws StorageException;
```

Default implementation returns empty stream (for non-PostgreSQL).

### 2. Implement in DatabaseStorage

**Method signature:**
```java
@Override
public Stream<Device> getDevicesWithFilters(
    long userId,
    Columns columns,
    Long groupId,
    String status,
    String search,
    Date lastUpdateFrom,
    Date lastUpdateTo,
    String sortBy,
    boolean sortDescending,
    int offset,
    int limit) throws StorageException
```

**SQL building logic:**

1. Build SELECT clause (columns or *)
2. Build FROM clause: `FROM tc_devices d`
3. Build WHERE clause:
   - Permission subquery (same as existing `buildPermittedDeviceIdsSubquery()`)
   - Add groupId filter if provided
   - Add status filter if provided (handle "NR" specially)
   - Add lastUpdateFrom filter if provided
   - Add lastUpdateTo filter if provided
   - Add search filter if provided (name + uniqueId LIKE)
4. Build ORDER BY clause dynamically based on sortBy/sortDescending
5. Build LIMIT/OFFSET clause if limit > 0

**Parameter binding order:**
1. userId (for permission subquery, appears twice)
2. groupId (if provided)
3. status (if provided, not "NR")
4. lastUpdateFrom (if provided)
5. lastUpdateTo (if provided)
6. search term (if provided, appears twice for name and uniqueId)
7. limit (if > 0)
8. offset (if limit > 0)

### 3. Update DeviceResource.get()

**Logic:**
- If **any** new filter parameter is provided (`groupId != null || status != null || lastUpdateFrom != null || lastUpdateTo != null || sortBy != null`), use new `getDevicesWithFilters()` method
- Otherwise, use existing code path (backward compatible)

**Code structure:**
```java
@GET
public Response get(
    @QueryParam("all") boolean all, 
    @QueryParam("userId") long userId,
    @QueryParam("uniqueId") List<String> uniqueIds,
    @QueryParam("id") List<Long> deviceIds,
    @QueryParam("excludeAttributes") boolean excludeAttributes,
    @QueryParam("offset") int offset,
    @QueryParam("limit") int limit,
    @QueryParam("search") String search,
    // NEW PARAMETERS:
    @QueryParam("groupId") Long groupId,
    @QueryParam("status") String status,
    @QueryParam("sortBy") String sortBy,
    @QueryParam("sortOrder") String sortOrder,
    @QueryParam("lastUpdateFrom") Date lastUpdateFrom,
    @QueryParam("lastUpdateTo") Date lastUpdateTo) throws StorageException {
    
    // Existing logic for uniqueIds/deviceIds (unchanged)
    if (!uniqueIds.isEmpty() || !deviceIds.isEmpty()) {
        // ... existing code ...
        return Response.ok(result.stream()).build();
    }
    
    // Check if new filters are used
    boolean useNewFilters = groupId != null || status != null || 
                           lastUpdateFrom != null || lastUpdateTo != null || 
                           sortBy != null;
    
    if (useNewFilters) {
        // NEW CODE PATH: Use getDevicesWithFilters()
        Columns columns = excludeAttributes ? new Columns.Exclude("attributes") : new Columns.All();
        
        // Parse sortOrder
        boolean sortDescending = "desc".equalsIgnoreCase(sortOrder);
        
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
        
        // Get total count (for pagination)
        long totalElements = 0;
        if (offset > 0 || limit > 0) {
            // Count query (same filters, no LIMIT/OFFSET)
            try (Stream<Device> countStream = storage.getDevicesWithFilters(
                userId, columns, groupId, status, search, 
                lastUpdateFrom, lastUpdateTo, sortBy, sortDescending, 0, 0)) {
                totalElements = countStream.count();
            }
        }
        
        // Get filtered devices
        List<Device> content;
        try (Stream<Device> stream = storage.getDevicesWithFilters(
            userId, columns, groupId, status, search,
            lastUpdateFrom, lastUpdateTo, sortBy, sortDescending, offset, limit)) {
            content = stream.toList();
        }
        
        if (offset > 0 || limit > 0) {
            return Response.ok(new Page<>(content, totalElements, offset, limit)).build();
        } else {
            return Response.ok(content).build();
        }
        
    } else {
        // EXISTING CODE PATH: Backward compatible
        // ... existing code unchanged ...
    }
}
```

### 4. Count Query Optimization

For pagination, we need total count. Two options:

**Option A:** Run count query separately (simpler)
```sql
SELECT COUNT(*) FROM tc_devices d WHERE ... (same filters)
```

**Option B:** Use window function (more efficient, single query)
```sql
SELECT *, COUNT(*) OVER() AS total_count FROM tc_devices d WHERE ... LIMIT ? OFFSET ?
```

**Recommendation:** Option A (simpler, acceptable performance for device lists)

---

## Status Filter: "NR" (Never Reported)

**Special handling:**
- When `status = "NR"`: Filter `WHERE d.lastupdate IS NULL`
- This is separate from status field (device can have status="offline" but lastUpdate=NULL)
- If both `status` and `lastUpdateFrom` are provided, they combine with AND:
  - `status = "NR"` means `lastupdate IS NULL`
  - `lastUpdateFrom` means `lastupdate >= ?`
  - These conflict, so either:
    - Return empty result (logical AND: NULL >= date is false)
    - Or: if status="NR", ignore lastUpdateFrom/lastUpdateTo filters

**Decision:** If `status = "NR"`, ignore `lastUpdateFrom` and `lastUpdateTo` filters (they're incompatible).

---

## SQL Example (Full Query)

```sql
SELECT d.*
FROM tc_devices d
WHERE d.id IN (
    SELECT deviceid FROM tc_user_device WHERE userid = ?
    UNION
    SELECT DISTINCT devices.deviceid FROM tc_user_group
    INNER JOIN (...) AS all_groups ON ...
    INNER JOIN (...) AS devices ON ...
    WHERE tc_user_group.userid = ?
)
AND (? IS NULL OR d.groupid = ?)
AND (
    ? IS NULL OR
    (? = 'NR' AND d.lastupdate IS NULL) OR
    (? != 'NR' AND COALESCE(d.status, 'offline') = ?)
)
AND (? IS NULL OR d.lastupdate >= ?)
AND (? IS NULL OR d.lastupdate <= ?)
AND (
    ? IS NULL OR
    LOWER(d.name) LIKE LOWER(?) OR
    LOWER(d.uniqueid) LIKE LOWER(?)
)
ORDER BY 
  CASE 
    WHEN ? = 'name' THEN d.name
    WHEN ? = 'uniqueId' THEN d.uniqueid
    WHEN ? = 'groupId' THEN d.groupid::text
    WHEN ? = 'status' THEN COALESCE(d.status, 'offline')
    WHEN ? = 'lastUpdate' THEN d.lastupdate::text
    ELSE d.name
  END ASC
LIMIT ? OFFSET ?
```

**Parameter binding:**
1. userId (2x for permission subquery)
2. groupId (2x: check null, value)
3. status (4x: check null, check NR, check NR again, value)
4. lastUpdateFrom (2x: check null, value)
5. lastUpdateTo (2x: check null, value)
6. search (3x: check null, name pattern, uniqueId pattern)
7. sortBy (6x for CASE WHEN)
8. limit
9. offset

**Better:** Build SQL dynamically to avoid unnecessary parameters.

---

## Dynamic SQL Building (Recommended)

Build WHERE and ORDER BY clauses conditionally:

```java
StringBuilder sql = new StringBuilder("SELECT ");
// columns
sql.append("d.* FROM tc_devices d WHERE d.id IN (");
sql.append(buildPermittedDeviceIdsSubquery());
sql.append(")");

List<Object> params = new ArrayList<>();
int paramIndex = 0;

// Permission params (userId x2)
params.add(userId);
params.add(userId);
paramIndex += 2;

// groupId filter
if (groupId != null) {
    sql.append(" AND d.groupid = ?");
    params.add(groupId);
    paramIndex++;
}

// status filter
if (status != null && !status.isEmpty()) {
    String statusLower = status.toLowerCase();
    if ("nr".equals(statusLower)) {
        sql.append(" AND d.lastupdate IS NULL");
    } else {
        sql.append(" AND COALESCE(d.status, 'offline') = ?");
        params.add(statusLower);
        paramIndex++;
    }
}

// lastUpdateFrom filter (skip if status="NR")
if (lastUpdateFrom != null && (status == null || !"nr".equalsIgnoreCase(status))) {
    sql.append(" AND d.lastupdate >= ?");
    params.add(new Timestamp(lastUpdateFrom.getTime()));
    paramIndex++;
}

// lastUpdateTo filter (skip if status="NR")
if (lastUpdateTo != null && (status == null || !"nr".equalsIgnoreCase(status))) {
    sql.append(" AND d.lastupdate <= ?");
    params.add(new Timestamp(lastUpdateTo.getTime()));
    paramIndex++;
}

// search filter
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
```

---

## Implementation Checklist

- [ ] Add `getDevicesWithFilters()` to `Storage` interface (default: empty stream)
- [ ] Implement `getDevicesWithFilters()` in `DatabaseStorage` (PostgreSQL only)
- [ ] Build dynamic SQL with all filter clauses
- [ ] Handle "NR" status (null lastUpdate)
- [ ] Handle status filter (online/offline/unknown)
- [ ] Handle groupId filter
- [ ] Handle lastUpdateFrom/lastUpdateTo filters
- [ ] Handle search filter (name + uniqueId)
- [ ] Build dynamic ORDER BY clause
- [ ] Handle NULLS LAST/FIRST for lastUpdate sorting
- [ ] Add pagination (LIMIT/OFFSET)
- [ ] Add count query for pagination metadata
- [ ] Update `DeviceResource.get()` to use new method when filters present
- [ ] Add parameter validation (status, sortBy)
- [ ] Test backward compatibility (no new params = old behavior)
- [ ] Test all filter combinations
- [ ] Test pagination with filters
- [ ] Test "NR" status filter
- [ ] Test sorting by all fields

---

## Edge Cases

1. **status="NR" + lastUpdateFrom/To:** Ignore date filters (incompatible)
2. **Null lastUpdate sorting:** NULLS LAST when ASC, NULLS FIRST when DESC
3. **Empty search:** No filter applied
4. **Invalid status/sortBy:** Return 400 Bad Request with error message
5. **No devices match:** Return empty list (not error)
6. **Pagination beyond total:** Return empty list (not error)

---

## Testing

- [ ] Filter by groupId
- [ ] Filter by status (online/offline/unknown/NR)
- [ ] Filter by lastUpdateFrom
- [ ] Filter by lastUpdateTo
- [ ] Filter by lastUpdateFrom + lastUpdateTo (range)
- [ ] Filter by status="NR" (should ignore date filters)
- [ ] Sort by name (asc/desc)
- [ ] Sort by uniqueId (asc/desc)
- [ ] Sort by groupId (asc/desc)
- [ ] Sort by status (asc/desc)
- [ ] Sort by lastUpdate (asc/desc, nulls handling)
- [ ] Search (name + uniqueId)
- [ ] Combine all filters + sort + pagination
- [ ] Backward compatibility (no new params)
- [ ] Invalid parameters (400 errors)
