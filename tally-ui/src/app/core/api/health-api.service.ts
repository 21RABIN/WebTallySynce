import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface HealthResponse {
  [key: string]: unknown;
  status?: string;
}

@Injectable({
  providedIn: 'root',
})
export class HealthApiService {
  constructor(private http: HttpClient) {}

  getHealth(): Observable<HealthResponse> {
    return this.http.get<HealthResponse>('/api/health');
  }

  getReadiness(): Observable<HealthResponse> {
    return this.http.get<HealthResponse>('/api/health/readiness');
  }

  getCapabilities(): Observable<HealthResponse> {
    return this.http.get<HealthResponse>('/api/health/capabilities');
  }
}
