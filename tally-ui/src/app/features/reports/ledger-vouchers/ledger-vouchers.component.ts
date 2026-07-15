import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { Subscription } from 'rxjs';

import { CompanyContextService } from '../../../core/api/company-context.service';
import { CacheExportApiService, ExportFormat } from '../../../core/api/cache-export-api.service';
import { MastersApiService } from '../../../core/api/masters-api.service';
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

@Component({
  selector: 'app-ledger-vouchers',
  templateUrl: './ledger-vouchers.component.html',
  styleUrls: ['./ledger-vouchers.component.scss'],
})
export class LedgerVouchersComponent implements OnInit, OnDestroy {
  isLoading = false;
  isLoadingLedgers = false;
  hasLoaded = false;
  errorMessage = '';
  response: any = null;
  selectedCompany = '';
  voucherRowsData: Array<{
    date: string;
    ledger: string;
    voucherType: string;
    debitAmount: string;
    creditAmount: string;
  }> = [];
  dataSourceLabel = 'Ledger Vouchers';
  ledgerOptions: string[] = [];
  ledgerParents: Record<string, string> = {};
  responseMeta: any = null;
  private readonly subscriptions = new Subscription();
  private ledgerLoadSequence = 0;
  private reportLoadSequence = 0;

  readonly form = this.formBuilder.group({
    from_date: [toDateInputValue(currentFiscalYearStart()), Validators.required],
    to_date: [toDateInputValue(formatDateYmd(new Date())), Validators.required],
    ledger_name: [''],
  });

  constructor(
    private reportsApiService: ReportsApiService,
    private mastersApiService: MastersApiService,
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
      this.loadLedgerOptions();
    }));
    this.loadLedgerOptions();
    this.companyContextService.refreshCompanies();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  load(): void {
    if (this.form.invalid || this.isLoadingLedgers) {
      this.form.markAllAsTouched();
      return;
    }

    const requestId = ++this.reportLoadSequence;
    this.isLoading = true;
    this.hasLoaded = true;
    this.errorMessage = '';
    this.response = null;
    this.responseMeta = null;
    this.voucherRowsData = [];
    const { from_date, to_date, ledger_name } = this.form.getRawValue();
    const fromDate = toApiDateValue(from_date) || '';
    const toDate = toApiDateValue(to_date) || '';
    if (!(ledger_name || '').trim()) {
      this.loadDayBookFallback(fromDate, toDate, '', requestId);
      return;
    }
    this.reportsApiService.getLedgerVouchers(fromDate, toDate, ledger_name || '', this.selectedCompany).subscribe({
      next: (response) => {
        if (requestId !== this.reportLoadSequence) {
          return;
        }
        this.response = response;
        this.responseMeta = response?.meta || null;
        const reportRows = this.extractEnvelopeRows(response?.data || response);
        if (reportRows.length > 0) {
          this.voucherRowsData = reportRows;
          this.dataSourceLabel = this.responseMeta?.source === 'cache' ? 'Ledger Vouchers Cache' : 'Ledger Vouchers';
          this.isLoading = false;
          return;
        }
        this.loadDayBookFallback(fromDate, toDate, ledger_name || '', requestId);
      },
      error: (error: HttpErrorResponse) => {
        if (requestId !== this.reportLoadSequence) {
          return;
        }
        this.errorMessage = this.resolveError(error);
        this.voucherRowsData = [];
        this.isLoading = false;
      },
    });
  }

  private loadLedgerOptions(): void {
    const requestId = ++this.ledgerLoadSequence;
    this.isLoadingLedgers = true;
    this.errorMessage = '';
    this.ledgerOptions = [];
    this.ledgerParents = {};

    this.mastersApiService.getLedgers(this.selectedCompany).subscribe({
      next: (response) => {
        if (requestId !== this.ledgerLoadSequence) {
          return;
        }
        const rawLedgers = this.toArray(response?.data || response?.LEDGER);
        this.ledgerOptions = rawLedgers
          .map((item) => {
            const name = this.asText(item?.NAME ?? item?.name);
            const parent = this.asText(item?.PARENT ?? item?.parent);
            if (name) {
              this.ledgerParents[name] = parent;
            }
            return name;
          })
          .filter((name) => !!name)
          .sort((left, right) => left.localeCompare(right));

        const defaultLedger = this.resolveDefaultLedger();
        this.isLoadingLedgers = false;
        if (defaultLedger) {
          this.form.patchValue({ ledger_name: defaultLedger });
          this.load();
        } else {
          this.form.patchValue({ ledger_name: '' });
          this.load();
        }
      },
      error: (error: HttpErrorResponse) => {
        if (requestId !== this.ledgerLoadSequence) {
          return;
        }
        this.errorMessage = this.resolveLedgerError(error);
        this.isLoadingLedgers = false;
      },
    });
  }

  get hasVoucher(): boolean {
    return this.voucherRowsData.length > 0;
  }

  get summaryCards(): Array<{ label: string; value: string }> {
    const totalRows = this.voucherRowsData.length;
    const debitTotal = this.voucherRowsData.reduce((sum, row) => sum + this.parseAmount(row.debitAmount), 0);
    const creditTotal = this.voucherRowsData.reduce((sum, row) => sum + this.parseAmount(row.creditAmount), 0);
    const voucherTypes = Array.from(new Set(this.voucherRowsData.map((row) => row.voucherType).filter(Boolean)));

    return [
      { label: 'Ledger', value: this.form.get('ledger_name')?.value || 'All Ledgers' },
      { label: 'Vouchers', value: String(totalRows) },
      { label: 'Voucher Types', value: voucherTypes.join(', ') || '-' },
      { label: 'Debit Total', value: this.formatAmount(debitTotal) },
      { label: 'Credit Total', value: this.formatAmount(creditTotal) },
    ];
  }

  get voucherRows(): Array<{
    date: string;
    ledger: string;
    voucherType: string;
    debitAmount: string;
    creditAmount: string;
  }> {
    return this.voucherRowsData;
  }

  download(format: ExportFormat): void {
    const { from_date, to_date, ledger_name } = this.form.getRawValue();
    this.cacheExportApiService.downloadAndSave('reports', 'ledger-vouchers', format, {
      company: this.selectedCompany || '',
      from_date: toApiDateValue(from_date) || '',
      to_date: toApiDateValue(to_date) || '',
      ledger_name: ledger_name || '',
    });
  }

  private extractEnvelopeRows(response: any): Array<{
    date: string;
    ledger: string;
    voucherType: string;
    debitAmount: string;
    creditAmount: string;
  }> {
    if (Array.isArray(response)) {
      return response.map((row) => ({
        date: String(row?.date || '-'),
        ledger: String(row?.ledger || this.form.get('ledger_name')?.value || '-'),
        voucherType: String(row?.voucherType || '-'),
        debitAmount: String(row?.debitAmount || '-'),
        creditAmount: String(row?.creditAmount || '-'),
      })).filter((row) => row.date !== '-' || row.voucherType !== '-' || row.debitAmount !== '-' || row.creditAmount !== '-');
    }
    this.response = response;
    const dates = this.readArray('DSPVCHDATE');
    const ledgers = this.readArray('DSPVCHLEDACCOUNT');
    const voucherTypes = this.readArray('DSPVCHTYPE');
    const debitAmounts = this.readArray('DSPVCHDRAMT');
    const creditAmounts = this.readArray('DSPVCHCRAMT');
    const totalRows = Math.max(
      dates.length,
      ledgers.length,
      voucherTypes.length,
      debitAmounts.length,
      creditAmounts.length
    );

    return Array.from({ length: totalRows }, (_, index) => ({
      date: dates[index] || '-',
      ledger: ledgers[index] || this.form.get('ledger_name')?.value || '-',
      voucherType: voucherTypes[index] || '-',
      debitAmount: debitAmounts[index] || '-',
      creditAmount: creditAmounts[index] || '-',
    })).filter((row) => row.date !== '-' || row.voucherType !== '-' || row.debitAmount !== '-' || row.creditAmount !== '-');
  }

  private loadDayBookFallback(fromDate: string, toDate: string, ledgerName: string, requestId: number = this.reportLoadSequence): void {
    this.reportsApiService.getDayBook(fromDate, toDate, 'raw', this.selectedCompany).subscribe({
      next: (response) => {
        if (requestId !== this.reportLoadSequence) {
          return;
        }
        this.voucherRowsData = this.extractDayBookRows(response, ledgerName);
        this.responseMeta = response?.meta || null;
        this.dataSourceLabel = this.voucherRowsData.length > 0 ? 'Day Book Fallback' : 'Ledger Vouchers';
        this.isLoading = false;
      },
      error: () => {
        if (requestId !== this.reportLoadSequence) {
          return;
        }
        this.voucherRowsData = [];
        this.dataSourceLabel = 'Ledger Vouchers';
        this.isLoading = false;
      },
    });
  }

  private extractDayBookRows(response: any, ledgerName: string): Array<{
    date: string;
    ledger: string;
    voucherType: string;
    debitAmount: string;
    creditAmount: string;
  }> {
    const target = ledgerName.trim().toLowerCase();
    const includeAllLedgers = !target;
    const rows: Array<{
      date: string;
      ledger: string;
      voucherType: string;
      debitAmount: string;
      creditAmount: string;
    }> = [];

    const tallyMessages =
      this.toArray(response?.TALLYMESSAGE).length > 0
        ? this.toArray(response?.TALLYMESSAGE)
        : this.toArray(response?.ENVELOPE?.BODY?.DATA?.TALLYMESSAGE);

    for (const message of tallyMessages) {
      for (const voucher of this.toArray(message?.VOUCHER)) {
        if (!voucher || typeof voucher !== 'object') {
          continue;
        }
        const record = voucher as Record<string, unknown>;
        const voucherType =
          this.readValue(record['VOUCHERTYPENAME']) ||
          this.readValue(record['VCHTYPE']) ||
          this.readValue(record['@VCHTYPE']);
        const partyLedger = this.readValue(record['PARTYLEDGERNAME']) || this.readValue(record['PARTYNAME']);
        const directLedger = this.readValue(record['LEDGERNAME']);
        const entry = this.findMatchingLedgerEntry(record, ledgerName);
        const entryLedger = this.readValue(entry?.LEDGERNAME);
        const entryAmount = this.readValue(entry?.AMOUNT);
        const normalizedAmount = this.normalizeAmount(entryAmount);
        const matchesLedger = includeAllLedgers ||
          partyLedger.toLowerCase() === target ||
          directLedger.toLowerCase() === target ||
          entryLedger.toLowerCase() === target;

        if (!matchesLedger) {
          continue;
        }

        rows.push({
          date: this.readValue(record['DATE']) || '-',
          ledger: entryLedger || partyLedger || directLedger || ledgerName || '-',
          voucherType: voucherType || '-',
          debitAmount: normalizedAmount < 0 ? this.formatAmount(Math.abs(normalizedAmount)) : '-',
          creditAmount: normalizedAmount > 0 ? this.formatAmount(Math.abs(normalizedAmount)) : '-',
        });
      }
    }

    return rows;
  }

  private findMatchingLedgerEntry(record: Record<string, unknown>, ledgerName: string): Record<string, unknown> | null {
    const target = ledgerName.trim().toLowerCase();
    const entries = this.toArray(
      record['LEDGERENTRIES.LIST'] ||
      record['LEDGERENTRIES'] ||
      record['ALLLEDGERENTRIES.LIST'] ||
      record['ALLLEDGERENTRIES']
    );

    for (const entry of entries) {
      if (!entry || typeof entry !== 'object') {
        continue;
      }
      const entryRecord = entry as Record<string, unknown>;
      const name = this.readValue(entryRecord['LEDGERNAME']).toLowerCase();
      if (name === target) {
        return entryRecord;
      }
    }
    return null;
  }

  private resolveDefaultLedger(): string {
    return '';
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

  private toArray<T>(value: T | T[] | null | undefined): T[] {
    if (Array.isArray(value)) {
      return value;
    }
    return value ? [value] : [];
  }

  private asText(value: unknown): string {
    return typeof value === 'string' ? value : '';
  }

  private readValue(value: unknown): string {
    if (value === null || value === undefined || value === '') {
      return '';
    }
    if (Array.isArray(value)) {
      return value.map((item) => this.readValue(item)).filter(Boolean).join(', ');
    }
    if (typeof value === 'object') {
      return '';
    }
    return String(value);
  }

  private parseAmount(value: string): number {
    const normalized = Number(value);
    return Number.isFinite(normalized) ? normalized : 0;
  }

  private normalizeAmount(value: string): number {
    const normalized = Number(value);
    return Number.isFinite(normalized) ? normalized : 0;
  }

  private formatAmount(value: number): string {
    return value.toFixed(2);
  }

  private resolveError(error: HttpErrorResponse): string {
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    return 'Unable to load ledger vouchers.';
  }

  private resolveLedgerError(error: HttpErrorResponse): string {
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    return 'Unable to load ledger names from the backend.';
  }
}
