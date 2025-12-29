import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface LogEntry {
  fileName: string;
  lineNumber: number;
  content: string;
}

export interface LogSearchResponse {
  entries: LogEntry[];
  count: number;
  query?: string;
  file?: string;
  error?: string;
}

export interface LogFilesResponse {
  files: string[];
  count: number;
}

@Injectable({
  providedIn: 'root'
})
export class LogSearchService {
  private apiUrl = '/api/logs';

  constructor(private http: HttpClient) {}

  getAvailableLogFiles(): Observable<LogFilesResponse> {
    return this.http.get<LogFilesResponse>(`${this.apiUrl}/files`);
  }

  searchLogs(
    query?: string,
    file?: string,
    maxLines: number = 1000,
    fromDate?: string,
    toDate?: string
  ): Observable<LogSearchResponse> {
    let params = new HttpParams();
    if (query) {
      params = params.set('query', query);
    }
    if (file) {
      params = params.set('file', file);
    }
    params = params.set('maxLines', maxLines.toString());
    if (fromDate) {
      params = params.set('fromDate', fromDate);
    }
    if (toDate) {
      params = params.set('toDate', toDate);
    }

    return this.http.get<LogSearchResponse>(`${this.apiUrl}/search`, { params });
  }

  getRecentLogs(file?: string, maxLines: number = 100): Observable<LogSearchResponse> {
    let params = new HttpParams().set('maxLines', maxLines.toString());
    if (file) {
      params = params.set('file', file);
    }

    return this.http.get<LogSearchResponse>(`${this.apiUrl}/recent`, { params });
  }
}

