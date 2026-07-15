import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

import { ReportsApiService } from './reports-api.service';

@Injectable({
  providedIn: 'root',
})
export class CompanyContextService {
  private readonly storageKey = 'tally-ui.selected-company';
  private readonly companiesSubject = new BehaviorSubject<string[]>([]);
  private readonly selectedCompanySubject = new BehaviorSubject<string>(this.readStoredCompany());

  readonly companies$ = this.companiesSubject.asObservable();
  readonly selectedCompany$ = this.selectedCompanySubject.asObservable();

  constructor(private reportsApiService: ReportsApiService) {}

  refreshCompanies(): void {
    this.reportsApiService.getCompaniesReport().subscribe({
      next: (response) => {
        const companies = Array.from(new Set(this.toArray(response?.data || response?.COMPANY)
          .map((item) => this.asText(item?.NAME ?? item?.name))
          .map((name) => name.trim())
          .filter((name) => !!name)));
        this.companiesSubject.next(companies);
        const current = this.selectedCompanySubject.value;
        if (!companies.length) {
          this.setSelectedCompany('');
          return;
        }
        const matchedCompany = companies.find((company) => this.isSameCompany(company, current));
        if (matchedCompany) {
          if (matchedCompany !== current) {
            this.setSelectedCompany(matchedCompany);
          }
          return;
        }
        this.setSelectedCompany(companies[0]);
      },
      error: () => {
        this.companiesSubject.next([]);
      },
    });
  }

  setSelectedCompany(company: string): void {
    const nextValue = this.resolveCompanySelection(company);
    const currentValue = this.selectedCompanySubject.value.trim();
    if (this.isSameCompany(currentValue, nextValue) && currentValue === nextValue) {
      return;
    }
    if (nextValue) {
      localStorage.setItem(this.storageKey, nextValue);
    } else {
      localStorage.removeItem(this.storageKey);
    }
    this.selectedCompanySubject.next(nextValue);
  }

  private resolveCompanySelection(company: string): string {
    const nextValue = (company || '').trim();
    const companies = this.companiesSubject.value;
    if (!nextValue || !companies.length) {
      return nextValue;
    }
    const matchedCompany = companies.find((item) => this.isSameCompany(item, nextValue));
    return matchedCompany || companies[0] || '';
  }

  private readStoredCompany(): string {
    return localStorage.getItem(this.storageKey) || '';
  }

  private toArray<T>(value: T | T[] | null | undefined): T[] {
    if (Array.isArray(value)) {
      return value;
    }
    return value ? [value] : [];
  }

  private asText(value: unknown): string {
    if (typeof value === 'string') {
      return value;
    }
    if (typeof value === 'number') {
      return String(value);
    }
    if (value && typeof value === 'object') {
      const record = value as Record<string, unknown>;
      if (typeof record['#text'] === 'string') {
        return record['#text'];
      }
      if (typeof record['#text'] === 'number') {
        return String(record['#text']);
      }
    }
    return '';
  }

  private isSameCompany(left: string, right: string): boolean {
    return left.trim().toLowerCase() === right.trim().toLowerCase();
  }
}
