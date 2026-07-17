import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root',
})
export class ApiWorkbenchService {
  constructor(private http: HttpClient) {}

  get(path: string, params?: Record<string, unknown>): Observable<string> {
    return this.http.get(path, {
      params: this.buildParams(params),
      responseType: 'text',
    });
  }

  post(
    path: string,
    body?: string | Record<string, unknown> | null,
    options?: { params?: Record<string, unknown>; contentType?: string }
  ): Observable<string> {
    return this.http.post(path, this.normalizeBody(body, options?.contentType), {
      params: this.buildParams(options?.params),
      headers: this.buildHeaders(options?.contentType),
      responseType: 'text',
    });
  }

  put(
    path: string,
    body?: string | Record<string, unknown> | null,
    options?: { params?: Record<string, unknown>; contentType?: string }
  ): Observable<string> {
    return this.http.put(path, this.normalizeBody(body, options?.contentType), {
      params: this.buildParams(options?.params),
      headers: this.buildHeaders(options?.contentType),
      responseType: 'text',
    });
  }

  delete(path: string, params?: Record<string, unknown>): Observable<string> {
    return this.http.delete(path, {
      params: this.buildParams(params),
      responseType: 'text',
    });
  }

  private buildParams(values?: Record<string, unknown>): HttpParams {
    let params = new HttpParams();
    Object.entries(values || {}).forEach(([key, value]) => {
      if (value === null || value === undefined || value === '') {
        return;
      }
      params = params.set(key, String(value));
    });
    return params;
  }

  private buildHeaders(contentType?: string): HttpHeaders | undefined {
    if (!contentType) {
      return undefined;
    }
    return new HttpHeaders({ 'Content-Type': contentType });
  }

  private normalizeBody(body?: string | Record<string, unknown> | null, contentType?: string): string | Record<string, unknown> | null {
    if (body === undefined) {
      return null;
    }
    if (typeof body === 'string' && contentType === 'application/json') {
      try {
        return JSON.parse(body) as Record<string, unknown>;
      } catch {
        return body;
      }
    }
    return body;
  }
}
