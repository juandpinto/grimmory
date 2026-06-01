package org.booklore.service.readthrough;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.ReadingGoalDto;
import org.booklore.model.dto.ReadthroughSummaryDto;
import org.booklore.model.dto.request.ReadingGoalRequest;
import org.booklore.model.dto.response.YearlySummaryResponse;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.UserReadingGoalEntity;
import org.booklore.repository.BookReadthroughRepository;
import org.booklore.repository.UserReadingGoalRepository;
import org.booklore.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReadingGoalService {

    private final UserReadingGoalRepository goalRepository;
    private final BookReadthroughRepository readthroughRepository;
    private final BookReadthroughService readthroughService;
    private final UserRepository userRepository;
    private final AuthenticationService authenticationService;

    @Transactional(readOnly = true)
    public Optional<ReadingGoalDto> getGoal(int year) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        return goalRepository.findByUserIdAndYear(userId, year)
                .map(this::toDto);
    }

    @Transactional
    public ReadingGoalDto setGoal(int year, ReadingGoalRequest request) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        UserReadingGoalEntity goal = goalRepository.findByUserIdAndYear(userId, year)
                .orElseGet(() -> {
                    BookLoreUserEntity user = userRepository.findById(userId)
                            .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("User not found: " + userId));
                    return UserReadingGoalEntity.builder()
                            .user(user)
                            .year(year)
                            .build();
                });
        goal.setBookGoal(request.getBookGoal());
        return toDto(goalRepository.save(goal));
    }

    @Transactional
    public void deleteGoal(int year) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        goalRepository.findByUserIdAndYear(userId, year)
                .ifPresent(goalRepository::delete);
    }

    @Transactional(readOnly = true)
    public YearlySummaryResponse getYearlySummary(int year) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        long booksRead = readthroughRepository.countByUserIdAndYear(userId, year);
        Long pagesRead = readthroughRepository.sumPagesByUserIdAndYear(userId, year);
        Integer goalValue = goalRepository.findByUserIdAndYear(userId, year)
                .map(UserReadingGoalEntity::getBookGoal)
                .orElse(null);

        List<Object[]> monthRows = readthroughRepository.countByUserIdAndYearGroupedByMonth(userId, year);
        List<YearlySummaryResponse.MonthlyCount> monthly = new ArrayList<>();
        for (Object[] row : monthRows) {
            monthly.add(YearlySummaryResponse.MonthlyCount.builder()
                    .month(((Number) row[0]).intValue())
                    .count(((Number) row[1]).longValue())
                    .build());
        }

        List<ReadthroughSummaryDto> readthroughs = readthroughService.listByUserAndYear(userId, year);

        Double avgDays = computeAvgDaysPerBook(readthroughs);
        Double expectedPace = goalValue != null ? computeExpectedByPace(year, goalValue) : null;

        return YearlySummaryResponse.builder()
                .year(year)
                .goal(goalValue)
                .booksRead(booksRead)
                .pagesRead(pagesRead)
                .avgDaysPerBook(avgDays)
                .expectedByPace(expectedPace)
                .monthlyBreakdown(monthly)
                .readthroughs(readthroughs)
                .build();
    }

    private Double computeAvgDaysPerBook(List<ReadthroughSummaryDto> readthroughs) {
        List<ReadthroughSummaryDto> withDates = readthroughs.stream()
                .filter(rt -> rt.getStartedOn() != null && rt.getFinishedOn() != null)
                .collect(Collectors.toList());
        if (withDates.isEmpty()) return null;
        double avg = withDates.stream()
                .mapToLong(rt -> rt.getStartedOn().until(rt.getFinishedOn(), java.time.temporal.ChronoUnit.DAYS))
                .average()
                .orElse(0.0);
        return avg;
    }

    private Double computeExpectedByPace(int year, int goal) {
        LocalDate today = LocalDate.now();
        int daysInYear = Year.of(year).length();
        int dayOfYear;
        if (today.getYear() > year) {
            // past year — full year
            dayOfYear = daysInYear;
        } else if (today.getYear() == year) {
            dayOfYear = today.getDayOfYear();
        } else {
            // future year
            dayOfYear = 0;
        }
        return goal * ((double) dayOfYear / daysInYear);
    }

    private ReadingGoalDto toDto(UserReadingGoalEntity entity) {
        return ReadingGoalDto.builder()
                .id(entity.getId())
                .year(entity.getYear())
                .bookGoal(entity.getBookGoal())
                .build();
    }
}
