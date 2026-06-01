package org.booklore.model.dto.request;

import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BookReadthroughRequest {
    private LocalDate startedOn;
    private LocalDate finishedOn;
    private String notes;
}
