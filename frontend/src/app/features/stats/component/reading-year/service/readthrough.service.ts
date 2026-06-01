import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../../../../core/config/api-config';
import {BookReadthroughDto, BookReadthroughRequest} from '../model/readthrough.model';

@Injectable({
  providedIn: 'root'
})
export class ReadthroughService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${API_CONFIG.BASE_URL}/api/v1/books`;

  getReadthroughs(bookId: number): Observable<BookReadthroughDto[]> {
    return this.http.get<BookReadthroughDto[]>(`${this.baseUrl}/${bookId}/readthroughs`);
  }

  createReadthrough(bookId: number, request: BookReadthroughRequest): Observable<BookReadthroughDto> {
    return this.http.post<BookReadthroughDto>(`${this.baseUrl}/${bookId}/readthroughs`, request);
  }

  updateReadthrough(bookId: number, readthroughId: number, request: BookReadthroughRequest): Observable<BookReadthroughDto> {
    return this.http.put<BookReadthroughDto>(`${this.baseUrl}/${bookId}/readthroughs/${readthroughId}`, request);
  }

  deleteReadthrough(bookId: number, readthroughId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${bookId}/readthroughs/${readthroughId}`);
  }
}
