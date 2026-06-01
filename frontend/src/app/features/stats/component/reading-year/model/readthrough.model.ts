export interface BookReadthroughDto {
  id: number;
  bookId: number;
  startedOn: string | null;
  finishedOn: string;
  notes: string | null;
  createdAt: string;
}

export interface ReadthroughSummaryDto {
  readthroughId: number;
  bookId: number;
  bookTitle: string | null;
  bookCoverUrl: string | null;
  authors: string | null;
  pageCount: number | null;
  startedOn: string | null;
  finishedOn: string;
  notes: string | null;
}

export interface BookReadthroughRequest {
  startedOn: string | null;
  finishedOn: string;
  notes: string | null;
}
