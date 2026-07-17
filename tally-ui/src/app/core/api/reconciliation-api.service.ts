import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root',
})
export class ReconciliationApiService {
  constructor(private http: HttpClient) {}

  getStatus(company: string): Observable<any> {
    const params = company ? new HttpParams().set('company', company) : undefined;
    return this.http.get('/api/reconciliation/status', { params });
  }

  getPreview(company: string): Observable<any> {
    const params = company ? new HttpParams().set('company', company) : undefined;
    return this.http.get('/api/reconciliation/preview', { params });
  }

  resolve(company: string, items: Array<Record<string, unknown>>): Observable<any> {
    return this.http.post('/api/reconciliation/resolve', { company, items });
  }

  sync(company: string, items: Array<Record<string, unknown>>): Observable<any> {
    return this.http.post('/api/reconciliation/sync', { company, items });
  }

  dismiss(company: string): Observable<any> {
    return this.http.post('/api/reconciliation/dismiss', { company });
  }
}
