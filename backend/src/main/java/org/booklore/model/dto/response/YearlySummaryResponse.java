package org.booklore.model.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.booklore.model.dto.ReadthroughSummaryDto;

import java.util.List;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class YearlySummaryResponse {
    private int year;
    private Integer goal;
    private long booksRead;
    private Long pagesRead;
    private Double avgDaysPerBook;
    private Double expectedByPace;
    private List<MonthlyCount> monthlyBreakdown;
    private List<ReadthroughSummaryDto> readthroughs;

    @Getter
    @Setter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MonthlyCount {
        private int month;
        private long count;
    }
}
