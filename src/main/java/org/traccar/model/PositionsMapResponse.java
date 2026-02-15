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
package org.traccar.model;

import java.util.List;

/**
 * Response for GET /api/positions with map bounds and zoom (positions + clusters).
 * Positions are minimal (id, deviceId, latitude, longitude, name, status) for map plotting only.
 */
public class PositionsMapResponse {

    private List<PositionMapItem> positions;
    private List<PositionCluster> clusters;

    public PositionsMapResponse() {
    }

    public PositionsMapResponse(List<PositionMapItem> positions, List<PositionCluster> clusters) {
        this.positions = positions;
        this.clusters = clusters;
    }

    public List<PositionMapItem> getPositions() {
        return positions;
    }

    public void setPositions(List<PositionMapItem> positions) {
        this.positions = positions;
    }

    public List<PositionCluster> getClusters() {
        return clusters;
    }

    public void setClusters(List<PositionCluster> clusters) {
        this.clusters = clusters;
    }

}
