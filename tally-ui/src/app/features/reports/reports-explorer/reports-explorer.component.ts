import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder } from '@angular/forms';
import { Subscription } from 'rxjs';

import { ApiWorkbenchService } from '../../../core/api/api-workbench.service';
import { CompanyContextService } from '../../../core/api/company-context.service';

type ReportField = 'from_date' | 'to_date' | 'ledger_name' | 'view';

type ReportDefinition = {
  key: string;
  label: string;
  path: string;
  description: string;
  fields: ReportField[];
};

const toDateInputValue = (value: string): string => /^\d{8}$/.test(value) ? `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)}` : value;
const toApiDateValue = (value: string | null | undefined): string => {
  const normalized = `${value || ''}`.trim();
  return /^\d{4}-\d{2}-\d{2}$/.test(normalized) ? normalized.replace(/-/g, '') : normalized;
};

const REPORT_DEFINITIONS: ReportDefinition[] = [
  { key: 'stock-summary', label: 'Stock Summary', path: '/api/cache/reports/stock-summary', description: 'Inventory summary view from the database-backed report cache.', fields: ['from_date', 'to_date'] },
  { key: 'outstanding-receivables', label: 'Outstanding Receivables', path: '/api/cache/reports/outstanding-receivables', description: 'Receivable ageing and outstanding amount details.', fields: ['from_date', 'to_date'] },
  { key: 'outstanding-payables', label: 'Outstanding Payables', path: '/api/cache/reports/outstanding-payables', description: 'Payable ageing and outstanding amount details.', fields: ['from_date', 'to_date'] },
  { key: 'batch-availability', label: 'Batch Availability', path: '/api/cache/reports/batch-availability', description: 'Batch-wise stock availability and quantity output.', fields: ['from_date', 'to_date'] },
  { key: 'price-lists', label: 'Price Lists', path: '/api/cache/reports/price-lists', description: 'Price structure and list data returned from the database-backed cache.', fields: [] },
  { key: 'bank-reco-status', label: 'Bank Reco Status', path: '/api/cache/reports/bank-reco-status', description: 'Bank reconciliation state and pending entries.', fields: ['from_date', 'to_date'] },
  { key: 'trial-balance', label: 'Trial Balance', path: '/api/cache/reports/trial-balance', description: 'Trial balance with ledger-side totals.', fields: ['from_date', 'to_date'] },
  { key: 'cash-book', label: 'Cash Book', path: '/api/cache/reports/cash-book', description: 'Cash book entries over the chosen period.', fields: ['from_date', 'to_date'] },
  { key: 'bank-book', label: 'Bank Book', path: '/api/cache/reports/bank-book', description: 'Bank book entries over the chosen period.', fields: ['from_date', 'to_date'] },
  { key: 'cash-flow', label: 'Cash Flow', path: '/api/cache/reports/cash-flow', description: 'Cash flow statement data.', fields: ['from_date', 'to_date'] },
  { key: 'funds-flow', label: 'Funds Flow', path: '/api/cache/reports/funds-flow', description: 'Funds flow statement data.', fields: ['from_date', 'to_date'] },
  { key: 'sales-register', label: 'Sales Register', path: '/api/cache/reports/sales-register', description: 'Sales register rows and totals.', fields: ['from_date', 'to_date'] },
  { key: 'sales-trend', label: 'Sales Trend', path: '/api/cache/reports/sales-trend', description: 'Sales trend output for the selected period.', fields: ['from_date', 'to_date'] },
  { key: 'purchase-register', label: 'Purchase Register', path: '/api/cache/reports/purchase-register', description: 'Purchase register rows and totals.', fields: ['from_date', 'to_date'] },
  { key: 'journal-register', label: 'Journal Register', path: '/api/cache/reports/journal-register', description: 'Journal voucher register output.', fields: ['from_date', 'to_date'] },
  { key: 'receipt-register', label: 'Receipt Register', path: '/api/cache/reports/receipt-register', description: 'Receipt voucher register output.', fields: ['from_date', 'to_date'] },
  { key: 'payment-register', label: 'Payment Register', path: '/api/cache/reports/payment-register', description: 'Payment voucher register output.', fields: ['from_date', 'to_date'] },
  { key: 'gstr-1', label: 'GSTR-1', path: '/api/cache/reports/gstr-1', description: 'GST outward filing report.', fields: ['from_date', 'to_date'] },
  { key: 'gstr-2', label: 'GSTR-2', path: '/api/cache/reports/gstr-2', description: 'GST inward filing report.', fields: ['from_date', 'to_date'] },
  { key: 'gstr-3b', label: 'GSTR-3B', path: '/api/cache/reports/gstr-3b', description: 'GST summary return data.', fields: ['from_date', 'to_date'] },
  { key: 'stock-ageing-analysis', label: 'Stock Ageing Analysis', path: '/api/cache/reports/stock-ageing-analysis', description: 'Ageing profile for stock and batch inventory.', fields: ['from_date', 'to_date'] },
  { key: 'movement-analysis', label: 'Movement Analysis', path: '/api/cache/reports/movement-analysis', description: 'Inventory movement analysis report.', fields: ['from_date', 'to_date'] },
  { key: 'reorder-status', label: 'Reorder Status', path: '/api/cache/reports/reorder-status', description: 'Reorder threshold status and replenishment data.', fields: [] },
  { key: 'form-26q', label: 'Form 26Q', path: '/api/cache/reports/form-26q', description: 'TDS return data for Form 26Q.', fields: ['from_date', 'to_date'] },
  { key: 'form-24q', label: 'Form 24Q', path: '/api/cache/reports/form-24q', description: 'TDS salary return data for Form 24Q.', fields: ['from_date', 'to_date'] },
  { key: 'form-27eq', label: 'Form 27EQ', path: '/api/cache/reports/form-27eq', description: 'TCS return data for Form 27EQ.', fields: ['from_date', 'to_date'] },
  { key: 'tds-outstandings', label: 'TDS Outstandings', path: '/api/cache/reports/tds-outstandings', description: 'Pending TDS liabilities and outstanding values.', fields: ['from_date', 'to_date'] },
  { key: 'cost-centre-breakup', label: 'Cost Centre Breakup', path: '/api/cache/reports/cost-centre-breakup', description: 'Cost-centre split-up and breakup view.', fields: ['from_date', 'to_date'] },
  { key: 'ratio-analysis', label: 'Ratio Analysis', path: '/api/cache/reports/ratio-analysis', description: 'Financial ratio analysis and summary output.', fields: ['from_date', 'to_date'] },
];

@Component({
  selector: 'app-reports-explorer',
  templateUrl: './reports-explorer.component.html',
  styleUrls: ['./reports-explorer.component.scss'],
})
export class ReportsExplorerComponent implements OnInit, OnDestroy {
  readonly reports = REPORT_DEFINITIONS;
  isLoading = false;
  errorMessage = '';
  rawResponse = '';
  responseSummary = '';
  tableRows: Array<Record<string, string>> = [];
  tableColumns: string[] = [];
  selectedCompany = '';
  private readonly subscriptions = new Subscription();

  readonly form = this.formBuilder.group({
    report: [this.reports[0].key],
    from_date: [toDateInputValue('20260401')],
    to_date: [toDateInputValue('20260523')],
    ledger_name: ['Cash'],
    view: ['raw'],
  });

  constructor(
    private apiWorkbenchService: ApiWorkbenchService,
    private formBuilder: FormBuilder,
    private companyContextService: CompanyContextService
  ) {}

  ngOnInit(): void {
    this.subscriptions.add(this.companyContextService.selectedCompany$.subscribe((company) => {
      this.selectedCompany = company || '';
    }));
    this.companyContextService.refreshCompanies();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  get selectedReport(): ReportDefinition {
    const selected = this.reports.find((item) => item.key === this.form.value.report);
    return selected || this.reports[0];
  }

  hasField(field: ReportField): boolean {
    return this.selectedReport.fields.includes(field);
  }

  load(): void {
    if (this.isLoading) {
      return;
    }

    this.isLoading = true;
    this.errorMessage = '';
    this.rawResponse = '';
    this.responseSummary = '';
    this.tableRows = [];
    this.tableColumns = [];

    this.apiWorkbenchService.get(this.selectedReport.path, this.buildParams()).subscribe({
      next: (response) => {
        this.rawResponse = this.formatResponse(response);
        this.hydratePreview(response);
        this.isLoading = false;
      },
      error: (error: HttpErrorResponse) => {
        this.errorMessage = this.resolveError(error);
        this.isLoading = false;
      },
    });
  }

  private buildParams(): Record<string, unknown> {
    const value = this.form.getRawValue();
    const params: Record<string, unknown> = {};
    if (this.selectedCompany.trim()) {
      params['company'] = this.selectedCompany.trim();
    }
    this.selectedReport.fields.forEach((field) => {
      const fieldValue = value[field];
      if (fieldValue) {
        params[field] = field === 'from_date' || field === 'to_date' ? toApiDateValue(String(fieldValue)) : fieldValue;
      }
    });
    return params;
  }

  private hydratePreview(response: string): void {
    const parsed = this.tryParseJson(response);
    if (!parsed) {
      this.responseSummary = 'Raw text response received from the backend.';
      return;
    }
    this.responseSummary = this.describeValue(parsed);
    const rows = this.extractRows(parsed);
    this.tableRows = rows;
    this.tableColumns = this.tableRows.length ? Object.keys(this.tableRows[0]) : [];
  }

  private extractRows(value: unknown): Array<Record<string, string>> {
    if (Array.isArray(value) && value.every((item) => item && typeof item === 'object' && !Array.isArray(item))) {
      return value.map((item) => this.normalizeRecord(item as Record<string, unknown>));
    }
    if (value && typeof value === 'object') {
      const record = value as Record<string, unknown>;
      for (const nested of Object.values(record)) {
        const rows = this.extractRows(nested);
        if (rows.length) {
          return rows;
        }
      }
      return [this.normalizeRecord(record)];
    }
    return [];
  }

  private normalizeRecord(record: Record<string, unknown>): Record<string, string> {
    const normalized: Record<string, string> = {};
    Object.entries(record).forEach(([key, value]) => {
      normalized[key] = this.asText(value);
    });
    return normalized;
  }

  private asText(value: unknown): string {
    if (value === null || value === undefined) {
      return '—';
    }
    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
      return String(value);
    }
    return JSON.stringify(value);
  }

  private describeValue(value: unknown): string {
    if (Array.isArray(value)) {
      return `${value.length} row(s) returned in the top-level response array.`;
    }
    if (value && typeof value === 'object') {
      return `${Object.keys(value as Record<string, unknown>).length} top-level field(s) returned from the backend.`;
    }
    return 'Structured response received.';
  }

  private tryParseJson(value: string): unknown | null {
    try {
      return JSON.parse(value);
    } catch {
      return null;
    }
  }

  private formatResponse(value: string): string {
    const parsed = this.tryParseJson(value);
    if (!parsed) {
      return value || 'No response body returned.';
    }
    return JSON.stringify(parsed, null, 2);
  }

  private resolveError(error: HttpErrorResponse): string {
    if (typeof error.error === 'string' && error.error.trim()) {
      return error.error;
    }
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    return 'Unable to load the selected report.';
  }
}
