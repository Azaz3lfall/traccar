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
 * Response for initial map load: bounds, zoom, device count, and plot data
 * (single positions + clusters) in one call.
 */
public class MapInitialResponse {

    private double minLat;
    private double maxLat;
    private double minLon;
    private double maxLon;
    private int zoom;
    private int deviceCount;
    private List<PositionMapItem> positions;
    private List<PositionCluster> clusters;

    public double getMinLat() {
        return minLat;
    }

    public void setMinLat(double minLat) {
        this.minLat = minLat;
    }

    public double getMaxLat() {
        return maxLat;
    }

    public void setMaxLat(double maxLat) {
        this.maxLat = maxLat;
    }

    public double getMinLon() {
        return minLon;
    }

    public void setMinLon(double minLon) {
        this.minLon = minLon;
    }

    public double getMaxLon() {
        return maxLon;
    }

    public void setMaxLon(double maxLon) {
        this.maxLon = maxLon;
    }

    public int getZoom() {
        return zoom;
    }

    public void setZoom(int zoom) {
        this.zoom = zoom;
    }

    public int getDeviceCount() {
        return deviceCount;
    }

    public void setDeviceCount(int deviceCount) {
        this.deviceCount = deviceCount;
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
