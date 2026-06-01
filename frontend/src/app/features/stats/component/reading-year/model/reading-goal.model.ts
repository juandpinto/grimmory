import {ReadthroughSummaryDto} from './readthrough.model';

export interface ReadingGoalDto {
  id: number;
  year: number;
  bookGoal: number;
}

export interface MonthlyCount {
  month: number;
  count: number;
}

export interface YearlySummaryResponse {
  year: number;
  goal: ReadingGoalDto | null;
  booksRead: number;
  totalPages: number;
  currentPace: number;
  monthlyBreakdown: MonthlyCount[];
  books: ReadthroughSummaryDto[];
}

export interface ReadingGoalRequest {
  bookGoal: number;
}
