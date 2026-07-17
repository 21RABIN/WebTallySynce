import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root',
})
export class SettingsApiService {
  constructor(private http: HttpClient) {}

  getCompanyCurrency(company?: string): Observable<any> {
    return this.http.get('/api/cache/settings/company-currency', {
      params: this.companyParams(company),
    });
  }

  updateCompanyCurrency(payload: Record<string, unknown>, company?: string): Observable<any> {
    return this.http.post('/api/settings/company-currency', payload, {
      params: this.companyParams(company),
    });
  }

  getCompanyFeatures(company?: string): Observable<any> {
    return this.http.get('/api/cache/settings/company-features', {
      params: this.companyParams(company),
    });
  }

  getNumberingRules(company?: string): Observable<any> {
    return this.http.get('/api/cache/settings/numbering-rules', {
      params: this.companyParams(company),
    });
  }

  updateCompanyFeatures(payload: Record<string, unknown>, company?: string): Observable<any> {
    return this.http.post('/api/settings/company-features', payload, {
      params: this.companyParams(company),
    });
  }

  private companyParams(company?: string): HttpParams {
    let params = new HttpParams();
    if (company && company.trim()) {
      params = params.set('company', company.trim());
    }
    return params;
  }
}
