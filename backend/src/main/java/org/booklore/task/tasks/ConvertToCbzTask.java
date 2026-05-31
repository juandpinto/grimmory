package org.booklore.task.tasks;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.service.book.BookConversionService;
import org.booklore.task.TaskStatus;
import org.booklore.task.options.ConvertToCbzOptions;
import org.springframework.stereotype.Component;

import static org.booklore.model.enums.UserPermission.CAN_MANAGE_LIBRARY;

@AllArgsConstructor
@Component
@Slf4j
public class ConvertToCbzTask implements Task {

    private final BookConversionService bookConversionService;

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        if (!CAN_MANAGE_LIBRARY.isGranted(user.getPermissions())) {
            throw ApiError.PERMISSION_DENIED.createException(CAN_MANAGE_LIBRARY);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        ConvertToCbzOptions options = request.getOptionsAs(ConvertToCbzOptions.class);
        if (options == null || options.getBookId() == null) {
            throw new IllegalArgumentException("ConvertToCbzOptions with a bookId is required");
        }

        String taskId = request.getTaskId();
        log.info("{}: Starting conversion for bookId={}, taskId={}", getTaskType(), options.getBookId(), taskId);

        bookConversionService.convertToCbz(options.getBookId(), taskId);

        log.info("{}: Conversion complete for bookId={}, taskId={}", getTaskType(), options.getBookId(), taskId);

        return TaskCreateResponse.builder()
                .taskType(TaskType.CONVERT_TO_CBZ)
                .taskId(taskId)
                .status(TaskStatus.COMPLETED)
                .build();
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.CONVERT_TO_CBZ;
    }
}
