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

type ProfitLossRow = {
  name: string;
  amount: number;
  formattedAmount: string;
  kind: string;
};

@Component({
  selector: 'app-profit-loss',
  templateUrl: './profit-loss.component.html',
  styleUrls: ['./profit-loss.component.scss'],
})
export class ProfitLossComponent implements OnInit, OnDestroy {
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
    this.reportsApiService.getProfitLoss(toApiDateValue(from_date) || '', toApiDateValue(to_date) || '', this.selectedCompany).subscribe({
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

  get rows(): ProfitLossRow[] {
    if (Array.isArray(this.response?.data)) {
      return this.response.data.map((row: any) => {
        const amount = this.parseAmount(String(row?.amount || '0'));
        return {
          name: String(row?.name || '-'),
          amount,
          formattedAmount: this.formatCurrency(Math.abs(amount)),
          kind: String(row?.kind || '').toUpperCase(),
        };
      }).filter((row: ProfitLossRow) => row.name !== '-');
    }
    const names = this.readArray('DSPACCNAME');
    const amounts = this.readAmountArray('PLAMT');
    const totalRows = Math.max(names.length, amounts.length);

    return Array.from({ length: totalRows }, (_, index) => {
      const amount = amounts[index] ?? 0;
      const name = names[index] || '-';
      return {
        name,
        amount,
        formattedAmount: this.formatCurrency(Math.abs(amount)),
        kind: this.isIncomeRow(name) ? 'INCOME' : 'EXPENSE',
      };
    }).filter((row) => row.name !== '-');
  }

  get leftRows(): ProfitLossRow[] {
    return this.rows.filter((row) => row.kind === 'EXPENSE');
  }

  get rightRows(): ProfitLossRow[] {
    return this.rows.filter((row) => row.kind === 'INCOME');
  }

  get displayLeftRows(): ProfitLossRow[] {
    const rows = [...this.leftRows];
    if (this.grossDifference > 0) {
      rows.push(this.buildDisplayRow('Gross Profit c/o', this.grossDifference, 'EXPENSE'));
    }
    return rows;
  }

  get displayRightRows(): ProfitLossRow[] {
    const rows = [...this.rightRows];
    if (this.grossDifference < 0) {
      rows.push(this.buildDisplayRow('Gross Loss c/o', Math.abs(this.grossDifference), 'INCOME'));
    }
    return rows;
  }

  get totalLeft(): number {
    return this.leftRows.reduce((sum, row) => sum + Math.abs(row.amount), 0);
  }

  get totalRight(): number {
    return this.rightRows.reduce((sum, row) => sum + Math.abs(row.amount), 0);
  }

  get statementDifference(): number {
    return Math.abs(this.grossDifference);
  }

  get statementTotal(): number {
    return Math.max(this.totalLeft, this.totalRight);
  }

  get grossProfit(): number {
    return this.grossDifference;
  }

  get netProfit(): number {
    return this.grossProfit;
  }

  get grossDifference(): number {
    return this.totalRight - this.totalLeft;
  }

  get formattedLeftTotal(): string {
    return this.formatCurrency(this.statementTotal);
  }

  get formattedRightTotal(): string {
    return this.formatCurrency(this.statementTotal);
  }

  get formattedGrossProfit(): string {
    return this.formatCurrency(Math.abs(this.grossProfit));
  }

  get formattedNetProfit(): string {
    return this.formatCurrency(Math.abs(this.netProfit));
  }

  get periodLabel(): string {
    return `${this.formatDisplayDate(this.form.value.from_date || '')} to ${this.formatDisplayDate(this.form.value.to_date || '')}`;
  }

  get cacheMeta(): any {
    return this.response?.meta || null;
  }

  get companyName(): string {
    return String(this.cacheMeta?.company || 'Company');
  }

  get resultLabel(): string {
    return this.netProfit >= 0 ? 'Net Profit' : 'Net Loss';
  }

  download(format: ExportFormat): void {
    const { from_date, to_date } = this.form.getRawValue();
    this.cacheExportApiService.downloadAndSave('reports', 'profit-loss', format, {
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
    const raw = this.readPrimitive(amountRecord.BSMAINAMT) || this.readPrimitive(amountRecord.PLSUBAMT) || '0';
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

  private isIncomeRow(name: string): boolean {
    const normalized = name.toLowerCase();
    return (
      normalized.includes('sales') ||
      normalized.includes('income') ||
      normalized.includes('closing stock') ||
      normalized.includes('direct income') ||
      normalized.includes('indirect income')
    );
  }

  private buildDisplayRow(name: string, amount: number, kind: string): ProfitLossRow {
    return {
      name,
      amount,
      formattedAmount: this.formatCurrency(Math.abs(amount)),
      kind,
    };
  }

  private parseAmount(value: string): number {
    const normalized = Number(value);
    return Number.isFinite(normalized) ? normalized : 0;
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
    return 'Unable to load the profit and loss report.';
  }
}
