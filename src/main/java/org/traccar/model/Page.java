package org.traccar.model;

import java.util.Collection;

public class Page<T> {

    private Collection<T> content;
    private long totalElements;
    private int offset;
    private int limit;
    /** Total devices with status online (devices list only). */
    private Long totalOnline;
    /** Total devices with status offline (devices list only). */
    private Long totalOffline;
    /** Total devices with status unknown (devices list only). */
    private Long totalUnknown;

    public Page(Collection<T> content, long totalElements, int offset, int limit) {
        this.content = content;
        this.totalElements = totalElements;
        this.offset = offset;
        this.limit = limit;
    }

    public Collection<T> getContent() {
        return content;
    }

    public void setContent(Collection<T> content) {
        this.content = content;
    }

    public long getTotalElements() {
        return totalElements;
    }

    public void setTotalElements(long totalElements) {
        this.totalElements = totalElements;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public Long getTotalOnline() {
        return totalOnline;
    }

    public void setTotalOnline(Long totalOnline) {
        this.totalOnline = totalOnline;
    }

    public Long getTotalOffline() {
        return totalOffline;
    }

    public void setTotalOffline(Long totalOffline) {
        this.totalOffline = totalOffline;
    }

    public Long getTotalUnknown() {
        return totalUnknown;
    }

    public void setTotalUnknown(Long totalUnknown) {
        this.totalUnknown = totalUnknown;
    }

}
