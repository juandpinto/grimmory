package org.booklore.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDate;

/**
 * A summary readthrough entry used in the yearly summary page book list.
 * Contains book metadata needed to render the list without a separate book fetch.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReadthroughSummaryDto {
    private Long readthroughId;
    private Long bookId;
    private String bookTitle;
    private String bookCoverUrl;
    private String authors;
    private Integer pageCount;
    private LocalDate startedOn;
    private LocalDate finishedOn;
    private String notes;
}
