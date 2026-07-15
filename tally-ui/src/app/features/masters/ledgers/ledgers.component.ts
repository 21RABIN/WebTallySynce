import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { Subscription } from 'rxjs';

import { CompanyContextService } from '../../../core/api/company-context.service';
import { CacheExportApiService, ExportFormat } from '../../../core/api/cache-export-api.service';
import { LedgerPayload, MastersApiService } from '../../../core/api/masters-api.service';
import { SyncMonitorService } from '../../../core/api/sync-monitor.service';

interface LedgerRecord {
  name: string;
  parent: string;
  reservedName: string;
}

@Component({
  selector: 'app-ledgers',
  templateUrl: './ledgers.component.html',
  styleUrls: ['./ledgers.component.scss'],
})
export class LedgersComponent implements OnInit, OnDestroy {
  ledgers: LedgerRecord[] = [];
  isLoading = true;
  isSubmitting = false;
  parentOptions: string[] = [];
  errorMessage = '';
  submitMessage = '';
  responseMeta: any = null;
  selectedCompany = '';
  selectedLedgerName = '';
  createMode = false;
  searchTerm = '';
  private readonly subscriptions = new Subscription();

  readonly form = this.formBuilder.group({
    NAME: ['', Validators.required],
    PARENT: ['', Validators.required],
  });

  constructor(
    private mastersApiService: MastersApiService,
    private formBuilder: FormBuilder,
    private companyContextService: CompanyContextService,
    private syncMonitorService: SyncMonitorService,
    private cacheExportApiService: CacheExportApiService
  ) {}

  ngOnInit(): void {
    this.subscriptions.add(this.companyContextService.selectedCompany$.subscribe((company) => {
      const nextCompany = company || '';
      if (nextCompany === this.selectedCompany) {
        return;
      }
      this.selectedCompany = nextCompany;
      this.loadParentOptions();
      this.load();
    }));
    this.subscriptions.add(this.syncMonitorService.refreshRequested$.subscribe(() => {
      this.loadParentOptions();
      this.load();
    }));
    this.loadParentOptions();
    this.load();
    this.companyContextService.refreshCompanies();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  get selectedLedger(): LedgerRecord | null {
    return this.ledgers.find((ledger) => ledger.name === this.selectedLedgerName) || null;
  }

  get detailTitle(): string {
    return this.createMode ? 'Ledger Creation' : 'Ledger Alteration';
  }

  get filteredLedgers(): LedgerRecord[] {
    const term = this.searchTerm.trim().toLowerCase();
    if (!term) {
      return this.ledgers;
    }
    return this.ledgers.filter((ledger) =>
      [ledger.name, ledger.parent, ledger.reservedName].some((value) => value.toLowerCase().includes(term))
    );
  }

  get groupedLedgerCount(): number {
    return this.ledgers.filter((ledger) => !!ledger.parent).length;
  }

  get reservedLedgerCount(): number {
    return this.ledgers.filter((ledger) => !!ledger.reservedName).length;
  }

  load(): void {
    this.isLoading = true;
    this.errorMessage = '';
    this.mastersApiService.getLedgers(this.selectedCompany).subscribe({
      next: (response) => {
        this.responseMeta = response?.meta || null;
        const rawLedgers = this.toArray(response?.data || response?.LEDGER);
        this.ledgers = rawLedgers
          .map((item) => ({
            name: this.readText(item, 'NAME', 'name'),
            parent: this.readText(item, 'PARENT', 'parent'),
            reservedName: this.readText(item, 'RESERVEDNAME', 'reserved_name'),
          }))
          .filter((item) => item.name)
          .sort((left, right) => left.name.localeCompare(right.name));
        this.syncSelection();
        this.isLoading = false;
      },
      error: (error: HttpErrorResponse) => {
        this.errorMessage = this.resolveError(error);
        this.isLoading = false;
      },
    });
  }

  submit(): void {
    if (this.form.invalid || this.isSubmitting) {
      this.form.markAllAsTouched();
      return;
    }

    this.isSubmitting = true;
    this.submitMessage = '';
    const payload = this.form.getRawValue() as LedgerPayload;

    this.mastersApiService.createLedger(payload, this.selectedCompany).subscribe({
      next: (response) => {
        this.isSubmitting = false;
        this.submitMessage = this.resolveSubmitMessage(payload.NAME, response);
        this.upsertLocalLedger(payload);
        this.selectLedger(payload.NAME);
        this.loadParentOptions();
        this.load();
      },
      error: (error: HttpErrorResponse) => {
        this.isSubmitting = false;
        this.submitMessage = '';
        this.errorMessage = this.resolveError(error);
      },
    });
  }

  private toArray<T>(value: T | T[] | null | undefined): T[] {
    if (Array.isArray(value)) {
      return value;
    }
    return value ? [value] : [];
  }

  private loadParentOptions(): void {
    this.mastersApiService.getGroups(this.selectedCompany).subscribe({
      next: (response) => {
        const rawGroups = this.toArray(response?.data || response?.GROUP);
        this.parentOptions = rawGroups
          .map((item) => this.readText(item, 'NAME', 'name'))
          .filter((name) => !!name)
          .sort((left, right) => left.localeCompare(right));
        const defaultParent = this.resolveDefaultParent();
        if (defaultParent) {
          this.form.patchValue({ PARENT: defaultParent });
        }
      },
      error: () => {
        this.parentOptions = [];
      },
    });
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
      const textValue = record['#text'];
      if (typeof textValue === 'string') {
        return textValue;
      }
      if (typeof textValue === 'number') {
        return String(textValue);
      }
    }
    return '';
  }

  private readText(value: unknown, ...keys: string[]): string {
    for (const key of keys) {
      if (value && typeof value === 'object' && key in (value as Record<string, unknown>)) {
        const next = this.asText((value as Record<string, unknown>)[key]);
        if (next) {
          return next;
        }
      }
    }
    return '';
  }

  private resolveDefaultParent(): string {
    if (this.parentOptions.includes('Capital Account')) {
      return 'Capital Account';
    }
    if (this.parentOptions.includes('Primary')) {
      return 'Primary';
    }
    return this.parentOptions[0] || '';
  }

  private resolveError(error: HttpErrorResponse): string {
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    if (error.error?.detail?.reason) {
      return String(error.error.detail.reason);
    }
    return 'Unable to load or create ledgers from the backend.';
  }

  private resolveSubmitMessage(ledgerName: string, response: any): string {
    if (response?.queued === true) {
      const queueId = response?.queue_id ? ` Queue ID: ${response.queue_id}.` : '';
      return `Ledger "${ledgerName}" was saved in the backend database and is waiting for sync approval.${queueId}`;
    }
    return `Ledger "${ledgerName}" created successfully.`;
  }

  private upsertLocalLedger(payload: LedgerPayload): void {
    const nextLedger: LedgerRecord = {
      name: this.asText(payload.NAME),
      parent: this.asText(payload.PARENT),
      reservedName: '',
    };
    this.ledgers = [...this.ledgers.filter((item) => item.name.toLowerCase() !== nextLedger.name.toLowerCase()), nextLedger]
      .sort((left, right) => left.name.localeCompare(right.name));
    this.syncSelection(nextLedger.name);
  }

  selectLedger(name: string): void {
    this.createMode = false;
    this.selectedLedgerName = name;
    const selectedLedger = this.ledgers.find((ledger) => ledger.name === name);
    this.form.reset({
      NAME: selectedLedger?.name || '',
      PARENT: selectedLedger?.parent || this.resolveDefaultParent(),
    });
  }

  openCreateMode(): void {
    this.createMode = true;
    this.selectedLedgerName = '';
    this.submitMessage = '';
    this.errorMessage = '';
    this.form.reset({
      NAME: '',
      PARENT: this.resolveDefaultParent(),
    });
  }

  download(format: ExportFormat): void {
    this.cacheExportApiService.downloadAndSave('masters', 'ledgers', format, {
      company: this.selectedCompany || '',
    });
  }

  clearSearch(): void {
    this.searchTerm = '';
  }

  private syncSelection(preferredName?: string): void {
    if (this.createMode) {
      return;
    }

    const nextSelection =
      preferredName && this.ledgers.some((ledger) => ledger.name === preferredName)
        ? preferredName
        : this.ledgers.some((ledger) => ledger.name === this.selectedLedgerName)
          ? this.selectedLedgerName
          : this.ledgers[0]?.name || '';

    if (nextSelection) {
      this.selectLedger(nextSelection);
      return;
    }

    this.selectedLedgerName = '';
    this.form.reset({
      NAME: '',
      PARENT: this.resolveDefaultParent(),
    });
  }
}
