import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { LoginPayload, LoginResponse } from '../auth/auth.service';

@Injectable({
  providedIn: 'root',
})
export class AuthApiService {
  constructor(private http: HttpClient) {}

  login(payload: LoginPayload): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(this.apiUrl('/api/auth/token'), payload);
  }

  private apiUrl(path: string): string {
    const apiBaseUrl = (environment.apiBaseUrl || '').replace(/\/$/, '');
    return apiBaseUrl ? `${apiBaseUrl}${path}` : path;
  }
}
