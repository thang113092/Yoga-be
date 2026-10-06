package com.company.yoga.common.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import org.springframework.data.domain.Page;

@Getter
@Schema(description = "Vỏ phản hồi phân trang chuẩn")
public class PageResponse<T> {

    @Schema(description = "Danh sách bản ghi của trang hiện tại")
    private final List<T> items;

    @Schema(description = "Số thứ tự trang (0-based)", example = "0")
    private final int pageNumber;

    @Schema(description = "Kích thước trang", example = "20")
    private final int pageSize;

    @Schema(description = "Tổng số phần tử", example = "100")
    private final long totalElements;

    @Schema(description = "Tổng số trang", example = "5")
    private final int totalPages;

    @Schema(description = "Trang đầu tiên?", example = "true")
    private final boolean first;

    @Schema(description = "Trang cuối cùng?", example = "false")
    private final boolean last;

    public PageResponse(Page<T> page) {
        this.items = page.getContent();
        this.pageNumber = page.getNumber();
        this.pageSize = page.getSize();
        this.totalElements = page.getTotalElements();
        this.totalPages = page.getTotalPages();
        this.first = page.isFirst();
        this.last = page.isLast();
    }

    public PageResponse(List<T> items, int pageNumber, int pageSize, long totalElements) {
        this.items = items != null ? items : Collections.emptyList();
        this.pageNumber = pageNumber;
        this.pageSize = pageSize;
        this.totalElements = totalElements;
        this.totalPages = pageSize > 0 ? (int) Math.ceil((double) totalElements / pageSize) : 0;
        this.first = pageNumber == 0;
        this.last = pageNumber >= totalPages - 1;
    }
}
