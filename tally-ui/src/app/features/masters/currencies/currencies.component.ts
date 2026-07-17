import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { Subscription } from 'rxjs';

import { CacheExportApiService, ExportFormat } from '../../../core/api/cache-export-api.service';
import { CompanyContextService } from '../../../core/api/company-context.service';
import { MastersApiService } from '../../../core/api/masters-api.service';

interface CurrencyRecord {
  name: string;
  originalName: string;
  symbol: string;
  decimalSymbol: string;
  decimalPlaces: string;
  warningNote: string;
}

@Component({
  selector: 'app-currencies',
  templateUrl: './currencies.component.html',
  styleUrls: ['./currencies.component.scss'],
})
export class CurrenciesComponent implements OnInit, OnDestroy {
  currencies: CurrencyRecord[] = [];
  isLoading = true;
  errorMessage = '';
  responseSource = '';
  selectedCompany = '';
  private readonly subscriptions = new Subscription();

  constructor(
    private mastersApiService: MastersApiService,
    private cacheExportApiService: CacheExportApiService,
    private companyContextService: CompanyContextService
  ) {}

  ngOnInit(): void {
    this.subscriptions.add(this.companyContextService.selectedCompany$.subscribe((company) => {
      const nextCompany = company || '';
      if (nextCompany === this.selectedCompany) {
        return;
      }
      this.selectedCompany = nextCompany;
      this.load();
    }));
    this.load();
    this.companyContextService.refreshCompanies();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  load(): void {
    this.isLoading = true;
    this.errorMessage = '';
    this.mastersApiService.getCurrencies(this.selectedCompany).subscribe({
      next: (response) => {
        const rawItems = this.toArray(response?.data ?? response?.items);
        this.currencies = rawItems
          .map((item) => ({
            name: this.asText(item?.name),
            originalName: this.asText(item?.originalName),
            symbol: this.asText(item?.symbol),
            decimalSymbol: this.asText(item?.decimalSymbol),
            decimalPlaces: this.asText(item?.decimalPlaces),
            warningNote: this.asText(item?.warning?.note),
          }))
          .filter((item) => item.name);
        this.responseSource = this.asText(response?.meta?.source || response?.source);
        this.isLoading = false;
      },
      error: (error: HttpErrorResponse) => {
        this.errorMessage = this.resolveError(error);
        this.isLoading = false;
      },
    });
  }

  download(format: ExportFormat): void {
    this.cacheExportApiService.downloadAndSave('masters', 'currencies', format, {
      company: this.selectedCompany || '',
    });
  }

  private toArray<T>(value: T | T[] | null | undefined): T[] {
    if (Array.isArray(value)) {
      return value;
    }
    return value ? [value] : [];
  }

  private asText(value: unknown): string {
    return typeof value === 'string' ? value : '';
  }

  private resolveError(error: HttpErrorResponse): string {
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    return 'Unable to load currencies from the backend.';
  }
}
