import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root',
})
export class CacheApiService {
  constructor(private http: HttpClient) {}

  getStatus(): Observable<any> {
    return this.http.get('/api/cache/status');
  }

  runSync(): Observable<any> {
    return this.http.post('/api/cache/sync', {});
  }
}
