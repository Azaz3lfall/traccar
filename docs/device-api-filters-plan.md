# Device API Filters Implementation Plan

## Current State

**Endpoint:** `GET /api/devices`

**Existing parameters:**
- `all` (boolean) - admin/manager all devices
- `userId` (long) - filter by user
- `uniqueId` (List<String>) - filter by unique IDs
- `id` (List<Long>) - filter by device IDs
- `excludeAttributes` (boolean) - exclude attributes column
- `offset` (int) - pagination offset
- `limit` (int) - pagination limit
- `search` (String) - **currently searches name and uniqueId** (already does what user wants)

**Current behavior:**
- Hardcoded sort: `Order("name")` (ascending by name)
- Search filter applies to both `name` and `uniqueId` fields (case-insensitive contains)
- No group filtering
- No status filtering
- No lastUpdate filtering

## Requirements

1. **Search filter** - Already implemented correctly (searches name + uniqueId)
2. **Sort by** - Add optional `sortBy` parameter
3. **Groups** - Add optional `groupId` parameter (single or list)
4. **Status** - Add optional `status` parameter (online/offline/unknown)
5. **Last update** - Add optional `lastUpdate` parameter (date range or threshold)

## Implementation Plan

### 1. Add Query Parameters

Add to `DeviceResource.get()` method signature:
```java
@QueryParam("groupId") List<Long> groupIds,           // Filter by group(s)
@QueryParam("status") String status,                   // Filter by status: "online", "offline", "unknown"
@QueryParam("sortBy") String sortBy,                   // Sort column: "name", "uniqueId", "lastUpdate", "status", "groupId"
@QueryParam("sortOrder") String sortOrder,             // "asc" or "desc" (default: "asc")
@QueryParam("lastUpdateFrom") Date lastUpdateFrom,     // Filter devices updated after this date
@QueryParam("lastUpdateTo") Date lastUpdateTo          // Filter devices updated before this date
```

### 2. Handle Group Filtering

**Implementation:**
- If `groupIds` is not empty, add `Condition.Equals("groupId", groupId)` for each groupId
- Use `Condition.Or` if multiple groups provided, or `Condition.In` if supported
- Note: `groupId` is a direct field on Device (from GroupedModel), so standard Condition works

**Code location:** In `get()` method, add to `conditions` list before calling `getFilteredStream()`

### 3. Handle Status Filtering

**Challenge:** `status` field is marked `@QueryIgnore` in Device model, meaning it's not queryable via standard Condition API.

**Options:**
- **Option A (Recommended):** Filter in Java after fetching from DB (post-filter)
  - Fetch devices with other filters
  - Filter stream by `device.getStatus().equals(status)` in `getFilteredStream()`
  - Pros: Works with existing code, no DB changes
  - Cons: Less efficient (fetches all then filters), but acceptable if combined with other filters

- **Option B:** Use custom SQL/Condition (if storage layer supports it)
  - Would require extending Condition API or using raw SQL
  - Pros: More efficient (DB-level filtering)
  - Cons: More complex, breaks abstraction

**Recommendation:** Use Option A (post-filter in Java stream) since status is already loaded and we're already doing stream filtering for search.

**Implementation:**
- In `getFilteredStream()`, after search filter, add status filter if provided
- Validate status value: must be one of `Device.STATUS_ONLINE`, `Device.STATUS_OFFLINE`, `Device.STATUS_UNKNOWN`

### 4. Handle Last Update Filtering

**Challenge:** `lastUpdate` field is also `@QueryIgnore`.

**Implementation:**
- Similar to status: post-filter in Java stream
- If `lastUpdateFrom` provided: filter `device.getLastUpdate() != null && device.getLastUpdate().after(lastUpdateFrom) || device.getLastUpdate().equals(lastUpdateFrom)`
- If `lastUpdateTo` provided: filter `device.getLastUpdate() != null && device.getLastUpdate().before(lastUpdateTo) || device.getLastUpdate().equals(lastUpdateTo)`
- Handle null `lastUpdate` (devices never updated): include/exclude based on business logic (probably exclude if `lastUpdateFrom` is set)

**Implementation:**
- Add to `getFilteredStream()` method, after status filter

### 5. Handle Sorting

**Current:** Hardcoded `new Order("name")`

**New:** Dynamic based on `sortBy` parameter

**Supported sort columns:**
- `"name"` → `Order("name", descending, 0)`
- `"uniqueId"` → `Order("uniqueId", descending, 0)`
- `"groupId"` → `Order("groupId", descending, 0)`
- `"lastUpdate"` → **Challenge:** `@QueryIgnore`, cannot sort in DB
- `"status"` → **Challenge:** `@QueryIgnore`, cannot sort in DB

**For `lastUpdate` and `status` sorting:**
- Option A: Sort in Java after fetching (using `Stream.sorted()`)
- Option B: Don't support DB-level sorting for these fields (return error or fallback to name)

**Recommendation:** Support Java-level sorting for `lastUpdate` and `status`:
- Fetch all matching devices (with other filters)
- Sort stream by `device.getLastUpdate()` or `device.getStatus()`
- Then apply pagination

**Implementation:**
- Parse `sortBy` parameter (default: `"name"`)
- Parse `sortOrder` parameter (default: `"asc"`, values: `"asc"` or `"desc"`)
- If `sortBy` is `"lastUpdate"` or `"status"`: use Java stream sorting
- Otherwise: use `Order` in `Request` constructor

### 6. Refactor `getFilteredStream()` Method

**Current signature:**
```java
private Stream<Device> getFilteredStream(
    Columns columns, List<Condition> conditions, String search)
```

**New signature:**
```java
private Stream<Device> getFilteredStream(
    Columns columns, List<Condition> conditions, String search,
    String status, Date lastUpdateFrom, Date lastUpdateTo)
```

**Changes:**
1. Apply search filter (name + uniqueId) - **already done**
2. Apply status filter if provided
3. Apply lastUpdate filters if provided
4. Return filtered stream

### 7. Update Main `get()` Method

**Changes:**
1. Parse new query parameters
2. Add group filter to `conditions` list (if `groupIds` not empty)
3. Determine sort strategy (DB vs Java)
4. If DB sort: pass `Order` to `Request` constructor
5. If Java sort: fetch all, sort stream, then paginate
6. Call `getFilteredStream()` with new parameters

### 8. Parameter Validation

- `status`: Must be one of `"online"`, `"offline"`, `"unknown"` (case-insensitive)
- `sortBy`: Must be one of `"name"`, `"uniqueId"`, `"groupId"`, `"lastUpdate"`, `"status"` (case-insensitive)
- `sortOrder`: Must be `"asc"` or `"desc"` (case-insensitive, default: `"asc"`)
- `lastUpdateFrom` and `lastUpdateTo`: Must be valid dates, `lastUpdateFrom <= lastUpdateTo` if both provided

## Implementation Steps

1. **Add query parameters** to `get()` method signature
2. **Add group filtering** to conditions list
3. **Refactor `getFilteredStream()`** to accept status and lastUpdate parameters
4. **Add status filtering** in `getFilteredStream()` (Java stream filter)
5. **Add lastUpdate filtering** in `getFilteredStream()` (Java stream filter)
6. **Add sorting logic** (DB vs Java based on field)
7. **Add parameter validation** (status, sortBy, sortOrder)
8. **Update pagination logic** to work with Java-sorted streams
9. **Test** with various combinations of filters

## Edge Cases

1. **Null lastUpdate:** Devices that never received a position update have `null` lastUpdate. Decide: include or exclude when filtering by `lastUpdateFrom`?
   - Recommendation: Exclude nulls when `lastUpdateFrom` is set (only show devices with updates)

2. **Multiple groups:** If multiple `groupId` values provided, should it be OR (any group) or AND (all groups)?
   - Recommendation: OR (device belongs to any of the specified groups)

3. **Status + lastUpdate combination:** Both filters apply independently (AND logic)

4. **Search + other filters:** All filters combine with AND logic

5. **Sorting null values:** For `lastUpdate` sorting, nulls should go last (or first?) 
   - Recommendation: Nulls last when ascending, first when descending

## Testing Checklist

- [ ] Filter by single group
- [ ] Filter by multiple groups (OR logic)
- [ ] Filter by status (online/offline/unknown)
- [ ] Filter by lastUpdateFrom
- [ ] Filter by lastUpdateTo
- [ ] Filter by lastUpdateFrom + lastUpdateTo (range)
- [ ] Sort by name (asc/desc)
- [ ] Sort by uniqueId (asc/desc)
- [ ] Sort by groupId (asc/desc)
- [ ] Sort by lastUpdate (asc/desc) - Java sort
- [ ] Sort by status (asc/desc) - Java sort
- [ ] Combine search + group + status + lastUpdate + sort
- [ ] Pagination works with all filters
- [ ] Invalid parameters return appropriate errors

## Notes

- **No frontend changes** - backend only, adds optional parameters
- **Backward compatible** - all new parameters are optional
- **Search already works** - searches both name and uniqueId as requested
- **Performance:** Java-level filtering for status/lastUpdate is acceptable since we're already doing stream operations for search. For large datasets, consider DB-level filtering later if needed.
