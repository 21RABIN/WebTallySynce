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

@Component({
  selector: 'app-day-book',
  templateUrl: './day-book.component.html',
  styleUrls: ['./day-book.component.scss'],
})
export class DayBookComponent implements OnInit {
  isLoading = false;
  errorMessage = '';
  response: any = null;
  selectedCompany = '';
  voucherRows: Array<{
    date: string;
    voucherType: string;
    voucherNumber: string;
    partyLedger: string;
    amount: string;
    narration: string;
    rawPayload: Record<string, unknown> | null;
  }> = [];
  selectedVoucher: {
    date: string;
    voucherType: string;
    voucherNumber: string;
    partyLedger: string;
    amount: string;
    narration: string;
    rawPayload: Record<string, unknown> | null;
  } | null = null;

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
    this.voucherRows = [];
    this.selectedVoucher = null;
    const { from_date, to_date } = this.form.getRawValue();
    this.reportsApiService.getDayBook(toApiDateValue(from_date) || '', toApiDateValue(to_date) || '', 'raw', this.selectedCompany).subscribe({
      next: (response) => {
        if (requestId !== this.loadSequence) {
          return;
        }
        this.response = response;
        this.voucherRows = this.extractVoucherRows(response);
        this.isLoading = false;
      },
      error: (error: HttpErrorResponse) => {
        if (requestId !== this.loadSequence) {
          return;
        }
        this.errorMessage = this.resolveError(error);
        this.voucherRows = [];
        this.isLoading = false;
      },
    });
  }

  get voucherCount(): number {
    return this.voucherRows.length;
  }

  get totalAmount(): string {
    const total = this.voucherRows.reduce((sum, voucher) => sum + this.parseAmount(voucher.amount), 0);
    return total.toFixed(2);
  }

  get selectedCompanyLabel(): string {
    return this.selectedCompany || 'No company selected';
  }

  get reportSource(): string {
    return String(this.response?.meta?.source || 'live');
  }

  get reportDateRangeLabel(): string {
    return `${this.form.value.from_date || '-'} to ${this.form.value.to_date || '-'}`;
  }

  get selectedVoucherInventoryEntries(): Array<{ item: string; actualQty: string; billedQty: string; rate: string; amount: string }> {
    const payload = this.selectedVoucher?.rawPayload;
    if (!payload) {
      return [];
    }
    return this.toArray(
      payload['ALLINVENTORYENTRIES.LIST'] ||
      payload['INVENTORYENTRIES.LIST'] ||
      payload['ALLINVENTORYENTRIES'] ||
      payload['INVENTORYENTRIES']
    )
      .filter((entry): entry is Record<string, unknown> => !!entry && typeof entry === 'object')
      .map((entry) => ({
        item: this.readValue(entry['STOCKITEMNAME']) || '-',
        actualQty: this.readValue(entry['ACTUALQTY']) || '-',
        billedQty: this.readValue(entry['BILLEDQTY']) || '-',
        rate: this.readValue(entry['RATE']) || '-',
        amount: this.readValue(entry['AMOUNT']) || '-',
      }));
  }

  get selectedVoucherLedgerEntries(): Array<{ ledger: string; amount: string; deemedPositive: string; partyLedger: string }> {
    const payload = this.selectedVoucher?.rawPayload;
    if (!payload) {
      return [];
    }
    const topLevelEntries = this.toArray(
      payload['ALLLEDGERENTRIES.LIST'] ||
      payload['LEDGERENTRIES.LIST'] ||
      payload['ALLLEDGERENTRIES'] ||
      payload['LEDGERENTRIES']
    )
      .filter((entry): entry is Record<string, unknown> => !!entry && typeof entry === 'object')
      .map((entry) => ({
        ledger: this.readValue(entry['LEDGERNAME']) || '-',
        amount: this.readValue(entry['AMOUNT']) || '-',
        deemedPositive: this.readValue(entry['ISDEEMEDPOSITIVE']) || 'No',
        partyLedger: this.readValue(entry['ISPARTYLEDGER']) || 'No',
      }));

    if (topLevelEntries.length) {
      return topLevelEntries.filter((entry, index, list) =>
        index === list.findIndex((candidate) => candidate.ledger === entry.ledger && candidate.amount === entry.amount)
      );
    }

    const inventoryAllocations = this.toArray(
      payload['ALLINVENTORYENTRIES.LIST'] ||
      payload['INVENTORYENTRIES.LIST'] ||
      payload['ALLINVENTORYENTRIES'] ||
      payload['INVENTORYENTRIES']
    )
      .filter((entry): entry is Record<string, unknown> => !!entry && typeof entry === 'object')
      .reduce<Record<string, unknown>[]>((all, entry) => {
        const allocations = this.toArray(
          entry['ACCOUNTINGALLOCATIONS.LIST'] ||
          entry['ACCOUNTINGALLOCATIONS'] ||
          entry['ACCOUNTINGALLOCATION']
        ).filter((allocation): allocation is Record<string, unknown> => !!allocation && typeof allocation === 'object');
        return [...all, ...allocations];
      }, [])
      .filter((allocation): allocation is Record<string, unknown> => !!allocation && typeof allocation === 'object')
      .map((entry) => ({
        ledger: this.readValue(entry['LEDGERNAME']) || '-',
        amount: this.readValue(entry['AMOUNT']) || '-',
        deemedPositive: this.readValue(entry['ISDEEMEDPOSITIVE']) || 'No',
        partyLedger: this.readValue(entry['ISPARTYLEDGER']) || 'No',
      }));

    const inventoryLedgerFallbacks = this.toArray(
      payload['ALLINVENTORYENTRIES.LIST'] ||
      payload['INVENTORYENTRIES.LIST'] ||
      payload['ALLINVENTORYENTRIES'] ||
      payload['INVENTORYENTRIES']
    )
      .filter((entry): entry is Record<string, unknown> => !!entry && typeof entry === 'object')
      .map((entry) => ({
        ledger: this.readValue(entry['ACCOUNTINGLEDGERNAME']) || '-',
        amount: this.readValue(entry['AMOUNT']) || '-',
        deemedPositive: 'Yes',
        partyLedger: 'No',
      }))
      .filter((entry) => entry.ledger !== '-');

    return [...inventoryAllocations, ...inventoryLedgerFallbacks].filter((entry, index, list) =>
      index === list.findIndex((candidate) => candidate.ledger === entry.ledger && candidate.amount === entry.amount)
    );
  }

  get selectedVoucherFields(): Array<{ label: string; value: string }> {
    const payload = this.selectedVoucher?.rawPayload;
    if (!payload) {
      return [];
    }
    const preferredKeys = [
      'DATE',
      'EFFECTIVEDATE',
      'VOUCHERTYPENAME',
      'VOUCHERNUMBER',
      'REFERENCE',
      'PARTYLEDGERNAME',
      'PERSISTEDVIEW',
      'ISINVOICE',
      'NARRATION',
    ];
    return preferredKeys
      .map((key) => ({
        label: key,
        value: key === 'VOUCHERNUMBER'
          ? (this.selectedVoucher?.voucherNumber || this.readValue(payload[key]) || '-')
          : (this.readValue(payload[key]) || '-'),
      }))
      .filter((item) => item.value !== '-');
  }

  get selectedVoucherRawJson(): string {
    if (!this.selectedVoucher?.rawPayload) {
      return '';
    }
    return JSON.stringify(this.selectedVoucher.rawPayload, null, 2);
  }

  get selectedVoucherDateLabel(): string {
    return this.formatVoucherDate(this.selectedVoucher?.date || '');
  }

  get selectedVoucherPartyName(): string {
    const payload = this.selectedVoucher?.rawPayload;
    if (!payload) {
      return this.selectedVoucher?.partyLedger || '-';
    }
    return this.readValue(payload['PARTYLEDGERNAME'])
      || this.readValue(payload['PARTYNAME'])
      || this.selectedVoucherLedgerEntries.find((entry) => entry.deemedPositive === 'Yes')?.ledger
      || this.selectedVoucher?.partyLedger
      || '-';
  }

  get selectedVoucherPrimaryLedger(): string {
    const entries = this.selectedVoucherLedgerEntries.filter((entry) => entry.ledger !== this.selectedVoucherPartyName);
    if (!entries.length) {
      return '-';
    }

    const preferredLedger = entries.find((entry) => this.isPreferredVoucherLedger(entry.ledger));
    if (preferredLedger) {
      return preferredLedger.ledger;
    }

    const nonTaxLedger = entries.find((entry) => !this.isTaxOrChargeLedger(entry.ledger));
    return nonTaxLedger?.ledger || entries[0]?.ledger || '-';
  }

  get selectedVoucherAmountLabel(): string {
    const payloadAmount = this.readValue(this.selectedVoucher?.rawPayload?.['AMOUNT']);
    if (payloadAmount) {
      return this.formatAbsoluteAmount(payloadAmount);
    }
    const selectedAmount = this.selectedVoucher?.amount || '0';
    if (this.parseAmount(selectedAmount) !== 0) {
      return this.formatAbsoluteAmount(selectedAmount);
    }
    const inventoryTotal = this.selectedVoucherInventoryEntries.reduce((sum, entry) => sum + Math.abs(this.parseAmount(entry.amount)), 0);
    return inventoryTotal.toFixed(2);
  }

  selectVoucher(voucher: {
    date: string;
    voucherType: string;
    voucherNumber: string;
    partyLedger: string;
    amount: string;
    narration: string;
    rawPayload: Record<string, unknown> | null;
  }): void {
    this.selectedVoucher = voucher;
  }

  closeVoucher(): void {
    this.selectedVoucher = null;
  }

  ledgerEntryDebit(amount: string): string {
    const value = this.parseAmount(amount);
    return value >= 0 ? this.formatAbsoluteAmount(amount) : '';
  }

  ledgerEntryCredit(amount: string): string {
    const value = this.parseAmount(amount);
    return value < 0 ? this.formatAbsoluteAmount(amount) : '';
  }

  inventoryEntryRateValue(rate: string): string {
    return rate.includes('/') ? rate.split('/')[0] || rate : rate;
  }

  inventoryEntryRateUnit(rate: string, qty: string): string {
    if (rate.includes('/')) {
      return rate.split('/')[1] || '';
    }
    const parts = qty.trim().split(/\s+/);
    return parts.length > 1 ? parts.slice(1).join(' ') : '';
  }

  inventoryEntryAmount(amount: string): string {
    return this.formatAbsoluteAmount(amount);
  }

  download(format: ExportFormat): void {
    const { from_date, to_date } = this.form.getRawValue();
    this.cacheExportApiService.downloadAndSave('reports', 'day-book', format, {
      company: this.selectedCompany || '',
      from_date: toApiDateValue(from_date) || '',
      to_date: toApiDateValue(to_date) || '',
    });
  }

  private extractVoucherRows(response: any): Array<{
    date: string;
    voucherType: string;
    voucherNumber: string;
    partyLedger: string;
    amount: string;
    narration: string;
    rawPayload: Record<string, unknown> | null;
  }> {
    if (Array.isArray(response?.data)) {
      return response.data.map((row: any) => {
        const rawPayload = this.parsePayload(row?.payloadJson ?? row?.payload_json, row);
        const voucherNumber = this.resolveVoucherDisplayNumber(rawPayload, row, String(row?.voucherNumber || '-'));
        return {
          date: String(row?.date || '-'),
          voucherType: String(row?.voucherType || '-'),
          voucherNumber,
          partyLedger: this.resolveVoucherParticular(rawPayload, String(row?.partyLedger || '')),
          amount: this.formatAbsoluteAmount(String(row?.amount || '0')),
          narration: String(row?.narration || '-'),
          rawPayload,
        };
      });
    }

    const tallyMessages =
      this.toArray(response?.TALLYMESSAGE).length > 0
        ? this.toArray(response?.TALLYMESSAGE)
        : this.toArray(response?.ENVELOPE?.BODY?.DATA?.TALLYMESSAGE);

    const rows: Array<{
      date: string;
      voucherType: string;
      voucherNumber: string;
      partyLedger: string;
      amount: string;
      narration: string;
      rawPayload: Record<string, unknown> | null;
    }> = [];

    for (const message of tallyMessages) {
      for (const voucher of this.toArray(message?.VOUCHER)) {
        if (!voucher || typeof voucher !== 'object') {
          continue;
        }
        const record = voucher as Record<string, unknown>;
        const voucherNumber = this.resolveVoucherDisplayNumber(
          record,
          null,
          this.readValue(record['VOUCHERNUMBER']) || this.readValue(record['REFERENCE']) || '-'
        );
        rows.push({
          date: this.readValue(record['DATE']) || '-',
          voucherType: this.readValue(record['VOUCHERTYPENAME']) || this.readValue(record['VOUCHERTYPE']) || this.readValue(record['@VCHTYPE']) || '-',
          voucherNumber,
          partyLedger: this.resolveVoucherParticular(record),
          amount: this.extractAmount(record),
          narration: this.readValue(record['NARRATION']) || '-',
          rawPayload: record,
        });
      }
    }
    return rows;
  }

  private parsePayload(value: unknown, row?: any): Record<string, unknown> | null {
    if (!value) {
      return this.applyVoucherIdentityMeta(null, row);
    }
    if (typeof value === 'object') {
      return this.applyVoucherIdentityMeta(this.unwrapVoucherPayload(value as Record<string, unknown>), row);
    }
    if (typeof value !== 'string') {
      return this.applyVoucherIdentityMeta(null, row);
    }
    try {
      const parsed = JSON.parse(value);
      return this.applyVoucherIdentityMeta(
        parsed && typeof parsed === 'object'
          ? this.unwrapVoucherPayload(parsed as Record<string, unknown>)
          : null,
        row
      );
    } catch {
      return this.applyVoucherIdentityMeta(null, row);
    }
  }

  private unwrapVoucherPayload(payload: Record<string, unknown> | null): Record<string, unknown> | null {
    if (!payload) {
      return null;
    }

    const wrappedVoucher = payload['VOUCHER'] ?? payload['voucher'];
    if (Array.isArray(wrappedVoucher)) {
      const firstVoucher = wrappedVoucher.find((entry): entry is Record<string, unknown> => !!entry && typeof entry === 'object');
      return firstVoucher || payload;
    }
    if (wrappedVoucher && typeof wrappedVoucher === 'object') {
      return wrappedVoucher as Record<string, unknown>;
    }
    return payload;
  }

  private resolveVoucherDisplayNumber(payload: Record<string, unknown> | null, row: any, fallback: string): string {
    const normalizedFallback = `${fallback || '-'}`.trim() || '-';
    const rowOriginalVoucherNumber = this.readValue(
      row?.originalVoucherNumber ??
      row?.original_voucher_number
    );
    const rowOfflineVoucherNumber = this.readValue(
      row?.offlineVoucherNumber ??
      row?.offline_voucher_number
    );
    if (rowOriginalVoucherNumber) {
      return rowOriginalVoucherNumber;
    }
    if (!payload) {
      return normalizedFallback.startsWith('OFF-') ? (rowOfflineVoucherNumber || normalizedFallback) : normalizedFallback;
    }

    const voucherNumber = this.readValue(payload['VOUCHERNUMBER']) || normalizedFallback;
    const reference = this.readValue(payload['REFERENCE']);
    const originalVoucherNumber = this.readValue(
      payload['ORIGINALVOUCHERNUMBER'] ??
      payload['originalVoucherNumber'] ??
      payload['original_voucher_number']
    );

    if (voucherNumber.toUpperCase().startsWith('OFF-')) {
      return rowOriginalVoucherNumber
        || originalVoucherNumber
        || (reference && !reference.toUpperCase().startsWith('OFF-') ? reference : '')
        || (normalizedFallback && !normalizedFallback.toUpperCase().startsWith('OFF-') ? normalizedFallback : '')
        || rowOfflineVoucherNumber
        || voucherNumber;
    }
    return voucherNumber || normalizedFallback;
  }

  private resolveVoucherParticular(payload: Record<string, unknown> | null, fallback = ''): string {
    if (!payload) {
      return fallback || '-';
    }

    const directName =
      this.readValue(payload['PARTYLEDGERNAME']) ||
      this.readValue(payload['PARTYNAME']) ||
      this.readValue(payload['LEDGERNAME']);

    if (directName) {
      return directName;
    }

    const topLevelLedger = this.toArray(
      payload['ALLLEDGERENTRIES.LIST'] ||
      payload['LEDGERENTRIES.LIST'] ||
      payload['ALLLEDGERENTRIES'] ||
      payload['LEDGERENTRIES']
    )
      .filter((entry): entry is Record<string, unknown> => !!entry && typeof entry === 'object')
      .find((entry) => this.readValue(entry['LEDGERNAME']));

    if (topLevelLedger) {
      const ledgerName = this.readValue(topLevelLedger['LEDGERNAME']);
      if (ledgerName) {
        return ledgerName;
      }
    }

    const inventoryAllocation = this.toArray(
      payload['ALLINVENTORYENTRIES.LIST'] ||
      payload['INVENTORYENTRIES.LIST'] ||
      payload['ALLINVENTORYENTRIES'] ||
      payload['INVENTORYENTRIES']
    )
      .filter((entry): entry is Record<string, unknown> => !!entry && typeof entry === 'object')
      .map((entry) => this.toArray(
        entry['ACCOUNTINGALLOCATIONS.LIST'] ||
        entry['ACCOUNTINGALLOCATIONS'] ||
        entry['ACCOUNTINGALLOCATION']
      ))
      .find((allocations) => allocations.length > 0);

    if (inventoryAllocation?.length) {
      const allocation = inventoryAllocation.find((entry): entry is Record<string, unknown> => !!entry && typeof entry === 'object');
      const ledgerName = allocation ? this.readValue(allocation['LEDGERNAME']) : '';
      if (ledgerName) {
        return ledgerName;
      }
    }

    return fallback || '-';
  }

  private applyVoucherIdentityMeta(payload: Record<string, unknown> | null, row?: any): Record<string, unknown> | null {
    if (!payload) {
      return payload;
    }
    const originalVoucherNumber = this.readValue(row?.originalVoucherNumber ?? row?.original_voucher_number);
    const offlineVoucherNumber = this.readValue(row?.offlineVoucherNumber ?? row?.offline_voucher_number);
    if (originalVoucherNumber) {
      payload['ORIGINALVOUCHERNUMBER'] = originalVoucherNumber;
      payload['originalVoucherNumber'] = originalVoucherNumber;
      payload['original_voucher_number'] = originalVoucherNumber;
    }
    if (offlineVoucherNumber) {
      payload['OFFLINEVOUCHERNUMBER'] = offlineVoucherNumber;
      payload['offlineVoucherNumber'] = offlineVoucherNumber;
      payload['offline_voucher_number'] = offlineVoucherNumber;
    }
    return payload;
  }

  private toArray<T>(value: T | T[] | null | undefined): T[] {
    if (Array.isArray(value)) {
      return value;
    }
    return value ? [value] : [];
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

  private extractAmount(voucher: Record<string, unknown>): string {
    const partyLedger = this.readValue(voucher['PARTYLEDGERNAME']) || this.readValue(voucher['PARTYNAME']);
    const ledgerEntries = this.toArray(voucher['LEDGERENTRIES.LIST'] || voucher['LEDGERENTRIES'] || voucher['ALLLEDGERENTRIES.LIST'] || voucher['ALLLEDGERENTRIES']);

    let fallbackAmount = '';
    for (const entry of ledgerEntries) {
      if (!entry || typeof entry !== 'object') {
        continue;
      }
      const record = entry as Record<string, unknown>;
      const ledgerName = this.readValue(record['LEDGERNAME']);
      const amount = this.readValue(record['AMOUNT']);
      if (!amount) {
        continue;
      }
      if (!fallbackAmount) {
        fallbackAmount = amount;
      }
      if (partyLedger && ledgerName === partyLedger) {
        return this.formatAbsoluteAmount(amount);
      }
    }

    const topAmount = this.readValue(voucher['AMOUNT']) || fallbackAmount;
    return topAmount ? this.formatAbsoluteAmount(topAmount) : '-';
  }

  private isPreferredVoucherLedger(ledgerName: string): boolean {
    const normalizedLedger = ledgerName.trim().toLowerCase();
    if (!normalizedLedger || this.isTaxOrChargeLedger(ledgerName)) {
      return false;
    }

    const voucherType = `${this.selectedVoucher?.voucherType || ''}`.trim().toLowerCase();
    if (voucherType.includes('sales')) {
      return normalizedLedger.includes('sales');
    }
    if (voucherType.includes('purchase')) {
      return normalizedLedger.includes('purchase');
    }
    if (voucherType.includes('receipt')) {
      return normalizedLedger.includes('receipt') || normalizedLedger.includes('cash') || normalizedLedger.includes('bank');
    }
    if (voucherType.includes('payment')) {
      return normalizedLedger.includes('payment') || normalizedLedger.includes('cash') || normalizedLedger.includes('bank');
    }
    return false;
  }

  private isTaxOrChargeLedger(ledgerName: string): boolean {
    const normalizedLedger = ledgerName.trim().toLowerCase();
    return [
      'gst',
      'cgst',
      'sgst',
      'igst',
      'cess',
      'tax',
      'tds',
      'tcs',
      'round',
      'freight',
      'delivery',
      'shipping',
      'charge',
      'charges',
      'discount',
      'commission',
      'output',
      'input',
    ].some((keyword) => normalizedLedger.includes(keyword));
  }

  private formatAbsoluteAmount(value: string): string {
    const amount = this.parseAmount(value);
    return Math.abs(amount).toFixed(2);
  }

  private formatTallyAmount(value: string): string {
    const amount = this.parseAmount(value);
    const absoluteAmount = this.formatAbsoluteAmount(value);
    return amount < 0 ? `(-)${absoluteAmount}` : absoluteAmount;
  }

  private formatVoucherDate(value: string): string {
    if (!/^\d{8}$/.test(value)) {
      return value || '-';
    }
    const year = value.slice(0, 4);
    const month = value.slice(4, 6);
    const day = value.slice(6, 8);
    return `${day}-${month}-${year}`;
  }

  private parseAmount(value: string): number {
    const normalized = Number(value);
    return Number.isFinite(normalized) ? normalized : 0;
  }

  private resolveError(error: HttpErrorResponse): string {
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    return 'Unable to load the day-book report.';
  }
}
