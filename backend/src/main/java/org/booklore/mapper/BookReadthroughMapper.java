package org.booklore.mapper;

import org.booklore.model.dto.BookReadthroughDto;
import org.booklore.model.entity.BookReadthroughEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BookReadthroughMapper {

    @Mapping(target = "bookId", source = "book.id")
    BookReadthroughDto toDto(BookReadthroughEntity entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "book", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    BookReadthroughEntity toEntity(BookReadthroughDto dto);
}
