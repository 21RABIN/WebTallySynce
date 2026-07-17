import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { tap } from 'rxjs/operators';

import { AuthApiService } from '../api/auth-api.service';

export interface LoginPayload {
  username: string;
  password: string;
  include_token: boolean;
}

export interface AuthUser {
  username: string;
  displayName?: string;
  roles?: string[];
}

export interface LoginResponse {
  message: string;
  token_type: string;
  expires_in: number;
  access_token?: string;
  user: AuthUser;
}

@Injectable({
  providedIn: 'root',
})
export class AuthService {
  private readonly tokenStorageKey = 'tally_ui_token';
  private readonly userStorageKey = 'tally_ui_user';
  private readonly currentUserSubject = new BehaviorSubject<AuthUser | null>(this.readStoredUser());

  readonly currentUser$ = this.currentUserSubject.asObservable();

  constructor(private authApiService: AuthApiService) {}

  login(username: string, password: string): Observable<LoginResponse> {
    const payload: LoginPayload = {
      username,
      password,
      include_token: true,
    };

    return this.authApiService.login(payload).pipe(
      tap((response) => {
        if (response.access_token) {
          localStorage.setItem(this.tokenStorageKey, response.access_token);
        }
        localStorage.setItem(this.userStorageKey, JSON.stringify(response.user));
        this.currentUserSubject.next(response.user);
      })
    );
  }

  logout(): void {
    localStorage.removeItem(this.tokenStorageKey);
    localStorage.removeItem(this.userStorageKey);
    this.currentUserSubject.next(null);
  }

  getToken(): string | null {
    const token = localStorage.getItem(this.tokenStorageKey);
    if (!token) {
      return null;
    }

    if (this.isTokenExpired(token)) {
      this.logout();
      return null;
    }

    return token;
  }

  getCurrentUser(): AuthUser | null {
    return this.currentUserSubject.value;
  }

  isAuthenticated(): boolean {
    return !!this.getToken();
  }

  private isTokenExpired(token: string): boolean {
    const payload = this.decodeTokenPayload(token);
    if (!payload || typeof payload.exp !== 'number') {
      return false;
    }

    const expiresAtMs = payload.exp * 1000;
    return Date.now() >= expiresAtMs;
  }

  private decodeTokenPayload(token: string): Record<string, unknown> | null {
    const parts = token.split('.');
    if (parts.length < 2) {
      return null;
    }

    try {
      const normalized = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      const decoded = atob(normalized.padEnd(normalized.length + ((4 - (normalized.length % 4)) % 4), '='));
      return JSON.parse(decoded) as Record<string, unknown>;
    } catch (error) {
      return null;
    }
  }

  private readStoredUser(): AuthUser | null {
    const rawValue = localStorage.getItem(this.userStorageKey);
    if (!rawValue) {
      return null;
    }

    try {
      return JSON.parse(rawValue) as AuthUser;
    } catch (error) {
      localStorage.removeItem(this.userStorageKey);
      return null;
    }
  }
}
