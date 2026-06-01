import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../../../../core/config/api-config';
import {ReadingGoalDto, ReadingGoalRequest, YearlySummaryResponse} from '../model/reading-goal.model';

@Injectable({
  providedIn: 'root'
})
export class ReadingGoalService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${API_CONFIG.BASE_URL}/api/v1/reading-goal`;

  getYearlySummary(year: number): Observable<YearlySummaryResponse> {
    return this.http.get<YearlySummaryResponse>(`${this.baseUrl}/${year}/summary`);
  }

  getGoal(year: number): Observable<ReadingGoalDto> {
    return this.http.get<ReadingGoalDto>(`${this.baseUrl}/${year}`);
  }

  setGoal(year: number, request: ReadingGoalRequest): Observable<ReadingGoalDto> {
    return this.http.put<ReadingGoalDto>(`${this.baseUrl}/${year}`, request);
  }

  deleteGoal(year: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${year}`);
  }
}
