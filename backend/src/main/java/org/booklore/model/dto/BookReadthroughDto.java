package org.booklore.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BookReadthroughDto {
    private Long id;
    private Long bookId;
    private LocalDate startedOn;
    private LocalDate finishedOn;
    private String notes;
    private LocalDateTime createdAt;
}
