package com.pojo;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int pageSize, long totalRows, int totalPages) {
    public List<T> getItems() { return items; }
    public int getPage() { return page; }
    public int getPageSize() { return pageSize; }
    public long getTotalRows() { return totalRows; }
    public int getTotalPages() { return totalPages; }
}

