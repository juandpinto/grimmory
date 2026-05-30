package org.booklore.model.dto.koreader;

import lombok.Data;

import java.util.List;

@Data
public class KoreaderStatsPayload {

    private List<KoreaderPageStat> stats;
}
