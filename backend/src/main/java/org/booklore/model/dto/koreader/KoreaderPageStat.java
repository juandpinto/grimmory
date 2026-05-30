package org.booklore.model.dto.koreader;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class KoreaderPageStat {

    @JsonProperty("book_md5")
    private String bookMd5;

    private Integer page;

    @JsonProperty("start_time")
    private Long startTime;

    private Integer duration;

    @JsonProperty("total_pages")
    private Integer totalPages;
}
