import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root',
})
export class ReportsApiService {
  constructor(private http: HttpClient) {}

  getDayBook(fromDate: string, toDate: string, view?: string, company?: string): Observable<any> {
    let params = new HttpParams().set('from_date', fromDate).set('to_date', toDate);
    if (view) {
      params = params.set('view', view);
    }
    if (company) {
      params = params.set('company', company);
    }
    return this.http.get('/api/cache/reports/day-book', { params });
  }

  getLedgerVouchers(fromDate: string, toDate: string, ledgerName: string, company?: string): Observable<any> {
    let params = new HttpParams()
      .set('from_date', fromDate)
      .set('to_date', toDate)
      .set('ledger_name', ledgerName);
    if (company) {
      params = params.set('company', company);
    }
    return this.http.get('/api/cache/reports/ledger-vouchers', { params });
  }

  getCompaniesReport(): Observable<any> {
    return this.http.get('/api/cache/masters/companies');
  }

  getBalanceSheet(fromDate: string, toDate: string, company?: string): Observable<any> {
    let params = new HttpParams().set('from_date', fromDate).set('to_date', toDate);
    if (company) {
      params = params.set('company', company);
    }
    return this.http.get('/api/cache/reports/balance-sheet', { params });
  }

  getProfitLoss(fromDate: string, toDate: string, company?: string): Observable<any> {
    let params = new HttpParams().set('from_date', fromDate).set('to_date', toDate);
    if (company) {
      params = params.set('company', company);
    }
    return this.http.get('/api/cache/reports/profit-loss', { params });
  }
}
