import { Component, OnDestroy, OnInit } from '@angular/core';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { Subscription } from 'rxjs';

import { CompanyContextService } from '../../../core/api/company-context.service';
import { PaymentReminderRecord, PaymentRemindersApiService } from '../../../core/api/payment-reminders-api.service';
import { ReportsApiService } from '../../../core/api/reports-api.service';

interface DashboardVoucherRow {
  date: string;
  voucherType: string;
  voucherNumber: string;
  partyLedger: string;
  amount: string;
  narration: string;
}

interface DashboardPartySummary {
  name: string;
  amount: number;
}

interface DashboardTrendPoint {
  label: string;
  value: number;
}

interface DashboardMetricRow {
  label: string;
  value: string;
  tone?: 'positive' | 'negative' | 'neutral';
}

@Component({
  selector: 'app-dashboard-home',
  templateUrl: './dashboard-home.component.html',
  styleUrls: ['./dashboard-home.component.scss'],
})
export class DashboardHomeComponent implements OnInit, OnDestroy {
  isLoading = true;
  errorMessage = '';
  reminders: PaymentReminderRecord[] = [];
  connectedCompanies = 0;
  dayBookRows: DashboardVoucherRow[] = [];
  assetTotal = 0;
  liabilityTotal = 0;
  cacheSources: string[] = [];
  selectedCompany = '';
  readonly rangeStart = '20260401';
  readonly rangeEnd = this.formatDateValue(new Date());
  private readonly subscriptions = new Subscription();
  private loadSequence = 0;

  readonly quickActions = [
    {
      label: 'Open Voucher',
      route: '/vouchers/create',
      description: 'Start voucher entry from the mobile-inspired workflow screen.',
    },
    {
      label: 'Receivable View',
      route: '/reports/ledger-vouchers',
      description: 'Review ledger-side voucher movement in a simpler business-facing report view.',
    },
    {
      label: 'Manage Ledgers',
      route: '/masters/ledgers',
      description: 'Review live ledgers and create master data without leaving the workspace.',
    },
    {
      label: 'Run Day Book',
      route: '/reports/day-book',
      description: 'Inspect voucher activity across a selected date range.',
    },
  ];

  constructor(
    private reportsApiService: ReportsApiService,
    private paymentRemindersApiService: PaymentRemindersApiService,
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
    this.companyContextService.refreshCompanies();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  load(): void {
    const company = this.selectedCompany || '';
    const requestId = ++this.loadSequence;

    this.isLoading = true;
    this.errorMessage = '';
    this.resetDashboardData();

    if (!company.trim()) {
      this.isLoading = false;
      return;
    }

    forkJoin({
      companies: this.reportsApiService.getCompaniesReport().pipe(catchError(() => of(null))),
      dayBook: this.reportsApiService.getDayBook(this.rangeStart, this.rangeEnd, 'raw', company).pipe(catchError(() => of(null))),
      balanceSheet: this.reportsApiService.getBalanceSheet(this.rangeStart, this.rangeEnd, company).pipe(catchError(() => of(null))),
      reminders: this.paymentRemindersApiService.getReminders().pipe(catchError(() => of({ reminders: [] }))),
    }).subscribe({
      next: (result) => {
        if (requestId !== this.loadSequence) {
          return;
        }
        this.connectedCompanies = this.extractCompaniesCount(result.companies?.data || result.companies);
        this.dayBookRows = this.extractVoucherRows(result.dayBook?.data || result.dayBook);
        const statementRows = this.extractBalanceRows(result.balanceSheet?.data || result.balanceSheet);
        this.liabilityTotal = statementRows
          .filter((row) => row.amount >= 0)
          .reduce((sum, row) => sum + row.amount, 0);
        this.assetTotal = statementRows
          .filter((row) => row.amount < 0)
          .reduce((sum, row) => sum + Math.abs(row.amount), 0);
        this.reminders = result.reminders?.reminders || [];
        this.cacheSources = [result.companies?.meta?.source, result.dayBook?.meta?.source, result.balanceSheet?.meta?.source]
          .filter((value): value is string => !!value);
        this.isLoading = false;
      },
      error: () => {
        if (requestId !== this.loadSequence) {
          return;
        }
        this.errorMessage = 'Live dashboard data could not be loaded. You can still use the rest of the workspace.';
        this.isLoading = false;
      },
    });
  }

  private resetDashboardData(): void {
    this.dayBookRows = [];
    this.assetTotal = 0;
    this.liabilityTotal = 0;
    this.reminders = [];
    this.cacheSources = [];
  }

  get totalVoucherAmount(): number {
    return this.dayBookRows.reduce((sum, row) => sum + this.parseAmount(row.amount), 0);
  }

  get salesTotal(): number {
    return this.sumVoucherAmountByType('sales');
  }

  get salesVoucherCount(): number {
    return this.countVoucherRowsByType('sales');
  }

  get purchaseTotal(): number {
    return this.sumVoucherAmountByType('purchase');
  }

  get purchaseVoucherCount(): number {
    return this.countVoucherRowsByType('purchase');
  }

  get receiptTotal(): number {
    return this.sumVoucherAmountByType('receipt');
  }

  get paymentTotal(): number {
    return this.sumVoucherAmountByType('payment');
  }

  get pendingReminderCount(): number {
    return this.reminders.filter((item) => item.status === 'PENDING').length;
  }

  get sentReminderCount(): number {
    return this.reminders.filter((item) => item.status === 'SENT').length;
  }

  get resolvedReminderCount(): number {
    return this.reminders.filter((item) => item.status === 'RESOLVED').length;
  }

  get cacheSourceLabel(): string {
    return this.cacheSources.length ? Array.from(new Set(this.cacheSources)).join(', ') : 'cache';
  }

  get periodLabel(): string {
    return `${this.formatDisplayDate(this.rangeStart)} to ${this.formatDisplayDate(this.rangeEnd)}`;
  }

  get topCustomers(): DashboardPartySummary[] {
    return this.buildTopPartySummary(['sales', 'receipt']);
  }

  get topSuppliers(): DashboardPartySummary[] {
    return this.buildTopPartySummary(['purchase', 'payment']);
  }

  get salesTrend(): DashboardTrendPoint[] {
    return this.buildMonthlyTrend(['sales']);
  }

  get purchaseTrend(): DashboardTrendPoint[] {
    return this.buildMonthlyTrend(['purchase']);
  }

  get cashFlowRows(): DashboardMetricRow[] {
    const netFlow = this.receiptTotal - this.paymentTotal;
    return [
      {
        label: 'Net Flow',
        value: this.formatCurrency(Math.abs(netFlow)),
        tone: netFlow >= 0 ? 'positive' : 'negative',
      },
      {
        label: 'Inflow',
        value: this.formatCurrency(this.receiptTotal),
        tone: 'positive',
      },
      {
        label: 'Outflow',
        value: this.formatCurrency(this.paymentTotal),
        tone: 'negative',
      },
    ];
  }

  get receivablePayableRows(): DashboardMetricRow[] {
    const receivables = this.topCustomers.reduce((sum, item) => sum + item.amount, 0);
    const payables = this.topSuppliers.reduce((sum, item) => sum + item.amount, 0);
    return [
      { label: 'Receivables', value: this.formatCurrency(receivables), tone: 'positive' },
      { label: 'Payables', value: this.formatCurrency(payables), tone: 'negative' },
      { label: 'Pending Reminders', value: String(this.pendingReminderCount), tone: 'neutral' },
      { label: 'Resolved Reminders', value: String(this.resolvedReminderCount), tone: 'positive' },
    ];
  }

  get accountingRatioRows(): DashboardMetricRow[] {
    const inventoryTurnover = this.salesTotal > 0 ? this.purchaseTotal / this.salesTotal : 0;
    const debtEquity = this.assetTotal > 0 ? this.liabilityTotal / this.assetTotal : 0;
    const collectionDays = this.salesTotal > 0
      ? (this.topCustomers.reduce((sum, item) => sum + item.amount, 0) / this.salesTotal) * 365
      : 0;
    const cashCoverage = this.paymentTotal > 0 ? this.receiptTotal / this.paymentTotal : 0;

    return [
      { label: 'Inventory Turnover', value: inventoryTurnover.toFixed(2), tone: 'neutral' },
      { label: 'Debt / Equity Ratio', value: debtEquity.toFixed(2), tone: 'neutral' },
      { label: 'Collection Cycle', value: `${collectionDays.toFixed(1)} days`, tone: 'neutral' },
      { label: 'Cash Coverage', value: `${cashCoverage.toFixed(2)}x`, tone: 'neutral' },
    ];
  }

  get tradingDetailRows(): DashboardMetricRow[] {
    const netMovement = this.salesTotal - this.purchaseTotal;
    return [
      { label: 'Sales Accounts', value: this.formatCurrency(this.salesTotal), tone: 'positive' },
      { label: 'Purchase Accounts', value: this.formatCurrency(this.purchaseTotal), tone: 'negative' },
      {
        label: 'Net Trading Movement',
        value: this.formatCurrency(Math.abs(netMovement)),
        tone: netMovement >= 0 ? 'positive' : 'negative',
      },
      { label: 'Voucher Throughput', value: String(this.dayBookRows.length), tone: 'neutral' },
    ];
  }

  get operatingSnapshotRows(): DashboardMetricRow[] {
    return [
      { label: 'Selected Company', value: this.selectedCompany || 'No company selected', tone: 'neutral' },
      { label: 'Connected Companies', value: String(this.connectedCompanies), tone: 'neutral' },
      { label: 'Voucher Rows', value: String(this.dayBookRows.length), tone: 'neutral' },
      { label: 'Dashboard Source', value: this.cacheSourceLabel, tone: 'neutral' },
    ];
  }

  formatCurrency(value: number): string {
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: 'INR',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  }

  buildTrendPath(points: DashboardTrendPoint[]): string {
    if (!points.length) {
      return '';
    }
    const width = 100;
    const height = 100;
    const step = points.length > 1 ? width / (points.length - 1) : width;
    const max = Math.max(...points.map((point) => point.value), 1);

    return points
      .map((point, index) => {
        const x = Number((index * step).toFixed(2));
        const y = Number((height - (point.value / max) * height).toFixed(2));
        return `${x},${y}`;
      })
      .join(' ');
  }

  buildTrendAreaPath(points: DashboardTrendPoint[]): string {
    const linePath = this.buildTrendPath(points);
    if (!linePath) {
      return '';
    }
    return `0,100 ${linePath} 100,100`;
  }

  private extractCompaniesCount(response: any): number {
    const items = Array.isArray(response) ? response : this.toArray(response?.COMPANY);
    return items.filter((item) => this.readValue(item?.NAME ?? item?.name)).length;
  }

  private extractVoucherRows(response: any): DashboardVoucherRow[] {
    if (Array.isArray(response)) {
      return response.map((row) => ({
        date: String(row?.date || '-'),
        voucherType: String(row?.voucherType || '-'),
        voucherNumber: String(row?.voucherNumber || '-'),
        partyLedger: String(row?.partyLedger || '-'),
        amount: String(row?.amount || '0'),
        narration: String(row?.narration || '-'),
      }));
    }
    const tallyMessages =
      this.toArray(response?.TALLYMESSAGE).length > 0
        ? this.toArray(response?.TALLYMESSAGE)
        : this.toArray(response?.ENVELOPE?.BODY?.DATA?.TALLYMESSAGE);

    const rows: DashboardVoucherRow[] = [];
    for (const message of tallyMessages) {
      for (const voucher of this.toArray(message?.VOUCHER)) {
        if (!voucher || typeof voucher !== 'object') {
          continue;
        }
        const record = voucher as Record<string, unknown>;
        rows.push({
          date: this.readValue(record['DATE']) || '-',
          voucherType:
            this.readValue(record['VOUCHERTYPENAME']) ||
            this.readValue(record['VOUCHERTYPE']) ||
            this.readValue(record['@VCHTYPE']) ||
            '-',
          voucherNumber: this.readValue(record['VOUCHERNUMBER']) || this.readValue(record['REFERENCE']) || '-',
          partyLedger:
            this.readValue(record['PARTYLEDGERNAME']) ||
            this.readValue(record['PARTYNAME']) ||
            this.readValue(record['LEDGERNAME']) ||
            '-',
          amount: this.extractAmount(record),
          narration: this.readValue(record['NARRATION']) || '-',
        });
      }
    }
    return rows;
  }

  private extractBalanceRows(response: any): Array<{ amount: number }> {
    if (Array.isArray(response)) {
      return response.map((row) => ({ amount: this.parseAmount(String(row?.amount || '0')) }));
    }
    const envelope = response?.ENVELOPE;
    const names = this.readArrayValue(envelope?.BSNAME);
    const values = Array.isArray(envelope?.BSAMT) ? envelope.BSAMT : envelope?.BSAMT ? [envelope.BSAMT] : [];
    const totalRows = Math.max(names.length, values.length);
    const rows: Array<{ amount: number }> = [];

    for (let index = 0; index < totalRows; index++) {
      const amount = this.readBalanceAmount(values[index]);
      if ((names[index] || '').trim()) {
        rows.push({ amount });
      }
    }

    return rows;
  }

  private readBalanceAmount(value: unknown): number {
    if (value === null || value === undefined || value === '') {
      return 0;
    }
    if (typeof value !== 'object') {
      return this.parseAmount(String(value));
    }
    const record = value as Record<string, unknown>;
    return this.parseAmount(
      this.readValue(record['BSMAINAMT']) ||
      this.readValue(record['BSSUBAMT']) ||
      '0'
    );
  }

  private buildTopPartySummary(voucherTypes: string[]): DashboardPartySummary[] {
    const totals = new Map<string, number>();
    const types = voucherTypes.map((item) => item.toLowerCase());

    for (const row of this.dayBookRows) {
      const rowType = row.voucherType.trim().toLowerCase();
      if (!types.includes(rowType)) {
        continue;
      }
      const party = row.partyLedger && row.partyLedger !== '-' ? row.partyLedger : 'Unknown';
      totals.set(party, (totals.get(party) || 0) + this.parseAmount(row.amount));
    }

    return Array.from(totals.entries())
      .map(([name, amount]) => ({ name, amount }))
      .sort((left, right) => right.amount - left.amount)
      .slice(0, 5);
  }

  private buildMonthlyTrend(voucherTypes: string[]): DashboardTrendPoint[] {
    const monthKeys = this.buildMonthKeys(this.rangeStart, this.rangeEnd);
    const totals = new Map<string, number>();
    const types = voucherTypes.map((item) => item.toLowerCase());

    for (const key of monthKeys) {
      totals.set(key, 0);
    }

    for (const row of this.dayBookRows) {
      const rowType = row.voucherType.trim().toLowerCase();
      if (!types.includes(rowType)) {
        continue;
      }

      const monthKey = this.extractMonthKey(row.date);
      if (!monthKey || !totals.has(monthKey)) {
        continue;
      }

      totals.set(monthKey, (totals.get(monthKey) || 0) + this.parseAmount(row.amount));
    }

    return monthKeys.map((key) => ({
      label: this.formatMonthLabel(key),
      value: totals.get(key) || 0,
    }));
  }

  private sumVoucherAmountByType(type: string): number {
    const normalized = type.toLowerCase();
    return this.dayBookRows
      .filter((row) => row.voucherType.trim().toLowerCase() === normalized)
      .reduce((sum, row) => sum + this.parseAmount(row.amount), 0);
  }

  private countVoucherRowsByType(type: string): number {
    const normalized = type.toLowerCase();
    return this.dayBookRows.filter((row) => row.voucherType.trim().toLowerCase() === normalized).length;
  }

  private extractAmount(voucher: Record<string, unknown>): string {
    const partyLedger = this.readValue(voucher['PARTYLEDGERNAME']) || this.readValue(voucher['PARTYNAME']);
    const ledgerEntries = this.toArray(
      voucher['LEDGERENTRIES.LIST'] ||
      voucher['LEDGERENTRIES'] ||
      voucher['ALLLEDGERENTRIES.LIST'] ||
      voucher['ALLLEDGERENTRIES']
    );

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
    return topAmount ? this.formatAbsoluteAmount(topAmount) : '0';
  }

  private formatAbsoluteAmount(value: string): string {
    return Math.abs(this.parseAmount(value)).toFixed(2);
  }

  private parseAmount(value: string): number {
    const normalized = Number(value);
    return Number.isFinite(normalized) ? normalized : 0;
  }

  private readArrayValue(value: unknown): string[] {
    if (value === null || value === undefined || value === '') {
      return [];
    }
    if (Array.isArray(value)) {
      return value.map((item) => this.readValue(item));
    }
    return [this.readValue(value)];
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
    return String(value).trim();
  }

  private formatDateValue(date: Date): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}${month}${day}`;
  }

  private buildMonthKeys(start: string, end: string): string[] {
    if (start.length < 6 || end.length < 6) {
      return [];
    }

    const keys: string[] = [];
    let cursorYear = Number(start.slice(0, 4));
    let cursorMonth = Number(start.slice(4, 6));
    const endYear = Number(end.slice(0, 4));
    const endMonth = Number(end.slice(4, 6));

    while (cursorYear < endYear || (cursorYear === endYear && cursorMonth <= endMonth)) {
      keys.push(`${cursorYear}${String(cursorMonth).padStart(2, '0')}`);
      cursorMonth += 1;
      if (cursorMonth > 12) {
        cursorMonth = 1;
        cursorYear += 1;
      }
    }

    return keys;
  }

  private extractMonthKey(value: string): string {
    const digits = value.replace(/\D/g, '');
    if (digits.length < 6) {
      return '';
    }
    return digits.slice(0, 6);
  }

  private formatMonthLabel(value: string): string {
    if (value.length !== 6) {
      return value;
    }
    const year = Number(value.slice(0, 4));
    const month = Number(value.slice(4, 6));
    const date = new Date(year, month - 1, 1);
    return new Intl.DateTimeFormat('en-IN', { month: 'short', year: '2-digit' }).format(date);
  }

  private formatDisplayDate(value: string): string {
    const digits = (value || '').replace(/\D/g, '');
    if (digits.length !== 8) {
      return value || '-';
    }
    const year = digits.slice(2, 4);
    const month = Number(digits.slice(4, 6));
    const day = String(Number(digits.slice(6, 8)));
    const monthName = new Intl.DateTimeFormat('en-IN', { month: 'short' }).format(new Date(Number(digits.slice(0, 4)), month - 1, 1));
    return `${day}-${monthName}-${year}`;
  }
}
