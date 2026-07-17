import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface GroupPayload {
  NAME: string;
  PARENT: string;
}

export interface LedgerPayload {
  NAME: string;
  PARENT: string;
}

export interface StockItemPayload {
  NAME: string;
  PARENT: string;
  BASEUNITS: string;
  HSNCODE?: string;
  GSTAPPLICABLE?: string;
  QUANTITY?: number | string;
  OPENINGBALANCE?: string;
  OPENINGRATE?: string;
  OPENINGVALUE?: number | string;
  RATEPER?: number | string;
}

export interface StockGroupPayload {
  NAME: string;
  PARENT?: string;
}

export interface UomPayload {
  NAME: string;
  ORIGINALNAME: string;
  ISSIMPLEUNIT: string;
}

@Injectable({
  providedIn: 'root',
})
export class MastersApiService {
  constructor(private http: HttpClient) {}

  private companyParams(company?: string, action?: string): HttpParams | undefined {
    let params = new HttpParams();
    if (action) {
      params = params.set('action', action);
    }
    if (company && company.trim()) {
      params = params.set('company', company.trim());
    }
    return params.keys().length ? params : undefined;
  }

  getGroups(company?: string): Observable<any> {
    return this.http.get('/api/cache/masters/groups', { params: this.companyParams(company) });
  }

  createGroup(payload: GroupPayload, company?: string): Observable<any> {
    return this.http.post('/api/groups', payload, {
      params: this.companyParams(company, 'Create'),
    });
  }

  getLedgers(company?: string): Observable<any> {
    return this.http.get('/api/cache/masters/ledgers', { params: this.companyParams(company) });
  }

  getStockGroups(company?: string): Observable<any> {
    return this.http.get('/api/cache/masters/stock-groups', { params: this.companyParams(company) });
  }

  createStockGroup(payload: StockGroupPayload, company?: string): Observable<any> {
    return this.http.post('/api/stock-groups', payload, {
      params: this.companyParams(company, 'Create'),
    });
  }

  getUoms(company?: string): Observable<any> {
    return this.http.get('/api/cache/masters/uoms', { params: this.companyParams(company) });
  }

  createUom(payload: UomPayload, company?: string): Observable<any> {
    return this.http.post('/api/uoms', payload, {
      params: this.companyParams(company, 'Create'),
    });
  }

  createLedger(payload: LedgerPayload, company?: string): Observable<any> {
    return this.http.post('/api/ledgers', payload, {
      params: this.companyParams(company, 'Create'),
    });
  }

  getStockItems(company?: string): Observable<any> {
    return this.http.get('/api/cache/masters/stock-items', { params: this.companyParams(company) });
  }

  createStockItem(payload: StockItemPayload, company?: string): Observable<any> {
    return this.http.post('/api/stock-items', payload, {
      params: this.companyParams(company, 'Create'),
    });
  }

  updateStockItem(payload: StockItemPayload, company?: string): Observable<any> {
    return this.http.post('/api/stock-items', payload, {
      params: this.companyParams(company, 'Alter'),
    });
  }

  getCurrencies(company?: string): Observable<any> {
    return this.http.get('/api/cache/masters/currencies', { params: this.companyParams(company) });
  }
}
