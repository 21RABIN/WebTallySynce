import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { Subscription } from 'rxjs';

import { CompanyContextService } from '../../../core/api/company-context.service';
import { CacheExportApiService, ExportFormat } from '../../../core/api/cache-export-api.service';
import { MastersApiService, StockGroupPayload } from '../../../core/api/masters-api.service';
import { SyncMonitorService } from '../../../core/api/sync-monitor.service';

interface StockGroupRecord {
  name: string;
  parent: string;
  reservedName: string;
}

@Component({
  selector: 'app-stock-groups',
  templateUrl: './stock-groups.component.html',
  styleUrls: ['./stock-groups.component.scss'],
})
export class StockGroupsComponent implements OnInit, OnDestroy {
  stockGroups: StockGroupRecord[] = [];
  parentOptions: string[] = [];
  isLoading = true;
  isSubmitting = false;
  errorMessage = '';
  submitMessage = '';
  selectedCompany = '';
  searchTerm = '';
  private readonly subscriptions = new Subscription();

  readonly form = this.formBuilder.group({
    NAME: ['', Validators.required],
    PARENT: [''],
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
      this.load();
    }));
    this.subscriptions.add(this.syncMonitorService.refreshRequested$.subscribe(() => {
      this.load();
    }));
    this.load();
    this.companyContextService.refreshCompanies();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  get filteredStockGroups(): StockGroupRecord[] {
    const term = this.searchTerm.trim().toLowerCase();
    if (!term) {
      return this.stockGroups;
    }
    return this.stockGroups.filter((group) =>
      [group.name, group.parent, group.reservedName].some((value) => value.toLowerCase().includes(term))
    );
  }

  get topLevelCount(): number {
    return this.stockGroups.filter((group) => !group.parent || group.parent.toLowerCase() === 'primary').length;
  }

  get nestedCount(): number {
    return Math.max(this.stockGroups.length - this.topLevelCount, 0);
  }

  load(): void {
    this.isLoading = true;
    this.errorMessage = '';
    this.mastersApiService.getStockGroups(this.selectedCompany).subscribe({
      next: (response) => {
        const rawStockGroups = this.toArray(response?.data || response?.STOCKGROUP);
        this.stockGroups = rawStockGroups
          .map((item) => ({
            name: this.readText(item, 'NAME', 'name'),
            parent: this.readText(item, 'PARENT', 'parent'),
            reservedName: this.readText(item, 'RESERVEDNAME', 'reserved_name'),
          }))
          .filter((item) => item.name)
          .sort((left, right) => left.name.localeCompare(right.name));
        this.parentOptions = this.resolveParentOptions(this.stockGroups.map((item) => item.name));
        const defaultParent = this.resolveDefaultParent();
        if (defaultParent) {
          this.form.patchValue({ PARENT: defaultParent });
        }
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
    const formValue = this.form.getRawValue();
    const selectedParent = (formValue.PARENT || '').trim();
    const payload: StockGroupPayload = {
      NAME: (formValue.NAME || '').trim(),
    };
    if (selectedParent && selectedParent.toLowerCase() !== 'primary') {
      payload.PARENT = selectedParent;
    }

    this.mastersApiService.createStockGroup(payload, this.selectedCompany).subscribe({
      next: (response) => {
        this.isSubmitting = false;
        this.submitMessage = this.resolveSubmitMessage(payload.NAME, response);
        this.upsertLocalStockGroup(payload);
        this.form.reset({ NAME: '', PARENT: '' });
        this.load();
      },
      error: (error: HttpErrorResponse) => {
        this.isSubmitting = false;
        this.submitMessage = '';
        this.errorMessage = this.resolveError(error);
      },
    });
  }

  clearSearch(): void {
    this.searchTerm = '';
  }

  download(format: ExportFormat): void {
    this.cacheExportApiService.downloadAndSave('masters', 'stock-groups', format, {
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
    return '';
  }

  private resolveParentOptions(names: string[]): string[] {
    const values = Array.from(new Set(names.filter((name) => !!name)));
    if (!values.includes('Primary')) {
      values.push('Primary');
    }
    return values;
  }

  private resolveError(error: HttpErrorResponse): string {
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    if (error.error?.detail?.reason) {
      return String(error.error.detail.reason);
    }
    return 'Unable to load or create stock groups from the backend.';
  }

  private resolveSubmitMessage(stockGroupName: string, response: any): string {
    if (response?.queued === true) {
      const queueId = response?.queue_id ? ` Queue ID: ${response.queue_id}.` : '';
      return `Stock group "${stockGroupName}" was saved in the backend database and is waiting for sync approval.${queueId}`;
    }
    return `Stock group "${stockGroupName}" created successfully.`;
  }

  private upsertLocalStockGroup(payload: StockGroupPayload): void {
    const nextStockGroup: StockGroupRecord = {
      name: this.asText(payload.NAME),
      parent: this.asText(payload.PARENT),
      reservedName: '',
    };
    this.stockGroups = [...this.stockGroups.filter((item) => item.name.toLowerCase() !== nextStockGroup.name.toLowerCase()), nextStockGroup]
      .sort((left, right) => left.name.localeCompare(right.name));
    this.parentOptions = this.resolveParentOptions(this.stockGroups.map((item) => item.name));
  }
}
