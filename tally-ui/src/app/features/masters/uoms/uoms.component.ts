import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { Subscription } from 'rxjs';

import { CompanyContextService } from '../../../core/api/company-context.service';
import { CacheExportApiService, ExportFormat } from '../../../core/api/cache-export-api.service';
import { MastersApiService, UomPayload } from '../../../core/api/masters-api.service';
import { SyncMonitorService } from '../../../core/api/sync-monitor.service';

interface UomRecord {
  name: string;
  reservedName: string;
  originalName: string;
  decimalPlaces: string;
}

@Component({
  selector: 'app-uoms',
  templateUrl: './uoms.component.html',
  styleUrls: ['./uoms.component.scss'],
})
export class UomsComponent implements OnInit, OnDestroy {
  uoms: UomRecord[] = [];
  isLoading = true;
  isSubmitting = false;
  lastSyncedAt = '';
  responseSource = '';
  errorMessage = '';
  submitMessage = '';
  selectedCompany = '';
  private readonly subscriptions = new Subscription();

  readonly form = this.formBuilder.group({
    NAME: ['', [Validators.required, Validators.pattern(/^[A-Za-z][A-Za-z ./&()-]{0,49}$/)]],
    ORIGINALNAME: ['', Validators.required],
    ISSIMPLEUNIT: ['Yes', Validators.required],
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

  load(): void {
    this.isLoading = true;
    this.errorMessage = '';
    this.mastersApiService.getUoms(this.selectedCompany).subscribe({
      next: (response) => {
        const rawUoms = this.toArray(response?.data ?? response?.UNIT);
        this.uoms = rawUoms
          .map((item) => ({
            name: this.firstName(this.readValue(item, 'NAME', 'name')),
            reservedName: this.readText(item, 'RESERVEDNAME', 'reserved_name'),
            originalName: this.readText(item, 'ORIGINALNAME', 'original_name'),
            decimalPlaces: this.readText(item, 'DECIMALPLACES', 'decimal_places'),
          }))
          .filter((item) => item.name)
          .sort((left, right) => left.name.localeCompare(right.name));
        this.lastSyncedAt = this.asText(response?.meta?.last_synced_at);
        this.responseSource = this.asText(response?.meta?.source);
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
    const payload = this.form.getRawValue() as UomPayload;

    this.mastersApiService.createUom(payload, this.selectedCompany).subscribe({
      next: (response) => {
        this.isSubmitting = false;
        const upstreamReason = this.resolveQueuedReason(response);
        if (upstreamReason) {
          this.submitMessage = '';
          this.errorMessage = upstreamReason;
          this.load();
          return;
        }
        this.submitMessage = this.resolveSubmitMessage(payload.NAME, response);
        this.errorMessage = '';
        this.upsertLocalUom(payload);
        this.form.reset({
          NAME: '',
          ORIGINALNAME: '',
          ISSIMPLEUNIT: 'Yes',
        });
        this.load();
      },
      error: (error: HttpErrorResponse) => {
        this.isSubmitting = false;
        this.submitMessage = '';
        this.errorMessage = this.resolveError(error);
      },
    });
  }

  download(format: ExportFormat): void {
    this.cacheExportApiService.downloadAndSave('masters', 'uoms', format, {
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

  private firstName(value: unknown): string {
    if (typeof value === 'string') {
      return value;
    }
    if (typeof value === 'number') {
      return String(value);
    }
    if (Array.isArray(value)) {
      const first = value.find((item) => typeof item === 'string' || typeof item === 'number');
      return first == null ? '' : String(first);
    }
    return '';
  }

  private readValue(value: unknown, ...keys: string[]): unknown {
    for (const key of keys) {
      if (value && typeof value === 'object' && key in (value as Record<string, unknown>)) {
        return (value as Record<string, unknown>)[key];
      }
    }
    return undefined;
  }

  private readText(value: unknown, ...keys: string[]): string {
    return this.asText(this.readValue(value, ...keys));
  }

  private resolveError(error: HttpErrorResponse): string {
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    if (error.error?.detail?.reason) {
      return String(error.error.detail.reason);
    }
    return 'Unable to load or create units from the backend.';
  }

  private resolveSubmitMessage(unitName: string, response: any): string {
    if (response?.queued === true) {
      const queueId = response?.queue_id ? ` Queue ID: ${response.queue_id}.` : '';
      return `Unit "${unitName}" was saved in the backend database and is waiting for sync approval.${queueId}`;
    }
    return `Unit "${unitName}" created successfully.`;
  }

  private resolveQueuedReason(response: any): string {
    const reason = this.asText(response?.upstream_response?.detail?.reason);
    if (!response?.queued || !reason) {
      return '';
    }
    if (/tallyprime is not available|tallyprime is not accepting xml requests/i.test(reason)) {
      return '';
    }
    return `Unit was not accepted by Tally: ${reason}`;
  }

  private upsertLocalUom(payload: UomPayload): void {
    const nextUom: UomRecord = {
      name: this.asText(payload.NAME),
      originalName: this.asText(payload.ORIGINALNAME),
      reservedName: '',
      decimalPlaces: '',
    };
    this.uoms = [...this.uoms.filter((item) => item.name.toLowerCase() !== nextUom.name.toLowerCase()), nextUom]
      .sort((left, right) => left.name.localeCompare(right.name));
  }
}
