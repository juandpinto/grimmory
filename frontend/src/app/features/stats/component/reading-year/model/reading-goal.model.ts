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
  goal: number | null;
  booksRead: number;
  pagesRead: number;
  avgDaysPerBook: number | null;
  expectedByPace: number | null;
  monthlyBreakdown: MonthlyCount[];
  readthroughs: ReadthroughSummaryDto[];
}

export interface ReadingGoalRequest {
  bookGoal: number;
}
