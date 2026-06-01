package org.booklore.model.dto.request;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReadingGoalRequest {
    private Integer bookGoal;
}
