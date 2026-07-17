import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit } from '@angular/core';

import { CacheExportApiService, ExportFormat } from '../../../core/api/cache-export-api.service';
import { ReportsApiService } from '../../../core/api/reports-api.service';

@Component({
  selector: 'app-companies-report',
  templateUrl: './companies-report.component.html',
  styleUrls: ['./companies-report.component.scss'],
})
export class CompaniesReportComponent implements OnInit {
  isLoading = true;
  errorMessage = '';
  response: any = null;

  constructor(
    private reportsApiService: ReportsApiService,
    private cacheExportApiService: CacheExportApiService
  ) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.isLoading = true;
    this.errorMessage = '';
    this.reportsApiService.getCompaniesReport().subscribe({
      next: (response) => {
        this.response = response;
        this.isLoading = false;
      },
      error: (error: HttpErrorResponse) => {
        this.errorMessage = this.resolveError(error);
        this.isLoading = false;
      },
    });
  }

  get companies(): Array<{ name: string; reservedName: string }> {
    const rawCompanies = this.toArray(this.response?.data || this.response?.COMPANY);
    return rawCompanies.map((company) => ({
      name: this.readValue(company?.NAME) || 'Unnamed Company',
      reservedName: this.readValue(company?.RESERVEDNAME),
    }));
  }

  get cacheMeta(): any {
    return this.response?.meta || null;
  }

  download(format: ExportFormat): void {
    this.cacheExportApiService.downloadAndSave('masters', 'companies', format);
  }

  private toArray<T>(value: T | T[] | null | undefined): T[] {
    if (Array.isArray(value)) {
      return value;
    }
    return value ? [value] : [];
  }

  private readValue(value: unknown): string {
    if (typeof value === 'string') {
      return value;
    }
    if (Array.isArray(value)) {
      const firstText = value.find((item) => typeof item === 'string');
      return typeof firstText === 'string' ? firstText : '';
    }
    return '';
  }

  private resolveError(error: HttpErrorResponse): string {
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    return 'Unable to load companies report.';
  }
}
