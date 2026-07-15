import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { Subscription } from 'rxjs';

import { CompanyContextService } from '../../../core/api/company-context.service';
import { CacheExportApiService, ExportFormat } from '../../../core/api/cache-export-api.service';
import { ReportsApiService } from '../../../core/api/reports-api.service';

const formatDateYmd = (date: Date): string => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}${month}${day}`;
};

const toDateInputValue = (value: string): string => /^\d{8}$/.test(value) ? `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)}` : value;
const toApiDateValue = (value: string | null | undefined): string => {
  const normalized = `${value || ''}`.trim();
  return /^\d{4}-\d{2}-\d{2}$/.test(normalized) ? normalized.replace(/-/g, '') : normalized;
};

const currentFiscalYearStart = (): string => {
  const now = new Date();
  const year = now.getMonth() >= 3 ? now.getFullYear() : now.getFullYear() - 1;
  return `${year}0401`;
};

type BalanceSheetRow = {
  name: string;
  amount: number;
  formattedAmount: string;
  kind: string;
};

@Component({
  selector: 'app-balance-sheet',
  templateUrl: './balance-sheet.component.html',
  styleUrls: ['./balance-sheet.component.scss'],
})
export class BalanceSheetComponent implements OnInit, OnDestroy {
  isLoading = false;
  errorMessage = '';
  response: any = null;
  selectedCompany = '';

  readonly form = this.formBuilder.group({
    from_date: [toDateInputValue(currentFiscalYearStart()), Validators.required],
    to_date: [toDateInputValue(formatDateYmd(new Date())), Validators.required],
  });
  private readonly subscriptions = new Subscription();
  private loadSequence = 0;

  constructor(
    private reportsApiService: ReportsApiService,
    private formBuilder: FormBuilder,
    private companyContextService: CompanyContextService,
    private cacheExportApiService: CacheExportApiService
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
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const requestId = ++this.loadSequence;
    this.isLoading = true;
    this.errorMessage = '';
    this.response = null;
    const { from_date, to_date } = this.form.getRawValue();
    this.reportsApiService.getBalanceSheet(toApiDateValue(from_date) || '', toApiDateValue(to_date) || '', this.selectedCompany).subscribe({
      next: (response) => {
        if (requestId !== this.loadSequence) {
          return;
        }
        this.response = response;
        this.isLoading = false;
      },
      error: (error: HttpErrorResponse) => {
        if (requestId !== this.loadSequence) {
          return;
        }
        this.errorMessage = this.resolveError(error);
        this.isLoading = false;
      },
    });
  }

  get hasRows(): boolean {
    return this.rows.length > 0;
  }

  get rows(): BalanceSheetRow[] {
    if (Array.isArray(this.response?.data)) {
      return this.response.data.map((row: any) => {
        const amount = this.parseAmount(String(row?.amount || '0'));
        return {
          name: String(row?.name || '-'),
          amount,
          formattedAmount: this.formatCurrency(Math.abs(amount)),
          kind: String(row?.kind || '').toUpperCase(),
        };
      }).filter((row: BalanceSheetRow) => row.name !== '-');
    }
    const names = this.readArray('BSNAME');
    const amounts = this.readAmountArray('BSAMT');
    const totalRows = Math.max(names.length, amounts.length);

    return Array.from({ length: totalRows }, (_, index) => {
      const amount = amounts[index] ?? 0;
      return {
        name: names[index] || '-',
        amount,
        formattedAmount: this.formatCurrency(Math.abs(amount)),
        kind: amount < 0 ? 'ASSET' : 'LIABILITY',
      };
    }).filter((row) => row.name !== '-');
  }

  get liabilityRows(): BalanceSheetRow[] {
    return this.rows.filter((row) => this.isLiabilityRow(row));
  }

  get assetRows(): BalanceSheetRow[] {
    return this.rows.filter((row) => this.isAssetRow(row));
  }

  get displayLiabilityRows(): BalanceSheetRow[] {
    const rows = [...this.liabilityRows];
    const difference = this.statementDifference;
    if (difference > 0) {
      rows.push(this.buildDisplayRow('Difference in opening balances', difference, 'LIABILITY'));
    }
    return rows;
  }

  get displayAssetRows(): BalanceSheetRow[] {
    const rows = [...this.assetRows];
    const difference = this.statementDifference;
    if (difference < 0) {
      rows.push(this.buildDisplayRow('Difference in opening balances', Math.abs(difference), 'ASSET'));
    }
    return rows;
  }

  get totalLiabilities(): number {
    return this.liabilityRows.reduce((sum, row) => sum + row.amount, 0);
  }

  get totalAssets(): number {
    return this.assetRows.reduce((sum, row) => sum + row.amount, 0);
  }

  get statementDifference(): number {
    return this.totalAssets - this.totalLiabilities;
  }

  get statementTotal(): number {
    return Math.max(this.totalAssets, this.totalLiabilities);
  }

  get formattedLiabilityTotal(): string {
    return this.formatCurrency(this.statementTotal);
  }

  get formattedAssetTotal(): string {
    return this.formatCurrency(this.statementTotal);
  }

  get reportDateLabel(): string {
    return this.formatDisplayDate(this.form.value.to_date || '');
  }

  get cacheMeta(): any {
    return this.response?.meta || null;
  }

  get companyName(): string {
    return String(this.cacheMeta?.company || 'Company');
  }

  download(format: ExportFormat): void {
    const { from_date, to_date } = this.form.getRawValue();
    this.cacheExportApiService.downloadAndSave('reports', 'balance-sheet', format, {
      company: this.selectedCompany || '',
      from_date: toApiDateValue(from_date) || '',
      to_date: toApiDateValue(to_date) || '',
    });
  }

  private get envelope(): Record<string, unknown> | null {
    const value = this.response?.ENVELOPE;
    return value && typeof value === 'object' ? (value as Record<string, unknown>) : null;
  }

  private readArray(key: string): string[] {
    const value = this.envelope?.[key];
    if (value === null || value === undefined || value === '') {
      return [];
    }
    if (Array.isArray(value)) {
      return value.map((item) => (item === null || item === undefined || item === '' ? '' : String(item)));
    }
    return [String(value)];
  }

  private readAmountArray(key: string): number[] {
    const value = this.envelope?.[key];
    const items = Array.isArray(value) ? value : value ? [value] : [];
    return items.map((item) => this.readAmountValue(item));
  }

  private readAmountValue(value: unknown): number {
    if (value === null || value === undefined || value === '') {
      return 0;
    }
    if (typeof value !== 'object') {
      return this.parseAmount(String(value));
    }
    const amountRecord = value as Record<string, unknown>;
    const raw = this.readPrimitive(amountRecord.BSMAINAMT) || this.readPrimitive(amountRecord.BSSUBAMT) || '0';
    return this.parseAmount(raw);
  }

  private readPrimitive(value: unknown): string {
    if (value === null || value === undefined || value === '') {
      return '';
    }
    if (Array.isArray(value)) {
      return value.map((item) => this.readPrimitive(item)).find(Boolean) || '';
    }
    if (typeof value === 'object') {
      return '';
    }
    return String(value).trim();
  }

  private parseAmount(value: string): number {
    const normalized = Number(value);
    return Number.isFinite(normalized) ? normalized : 0;
  }

  private isLiabilityRow(row: BalanceSheetRow): boolean {
    return row.kind === 'LIABILITY';
  }

  private isAssetRow(row: BalanceSheetRow): boolean {
    return row.kind === 'ASSET';
  }

  private buildDisplayRow(name: string, amount: number, kind: string): BalanceSheetRow {
    return {
      name,
      amount,
      formattedAmount: this.formatCurrency(Math.abs(amount)),
      kind,
    };
  }

  private formatCurrency(value: number): string {
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: 'INR',
      maximumFractionDigits: 2,
      minimumFractionDigits: 2,
    }).format(value);
  }

  private formatDisplayDate(value: string): string {
    if (!/^\d{8}$/.test(value)) {
      return value || '-';
    }
    return `${value.slice(6, 8)}/${value.slice(4, 6)}/${value.slice(0, 4)}`;
  }

  private resolveError(error: HttpErrorResponse): string {
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    return 'Unable to load the balance sheet report.';
  }
}
