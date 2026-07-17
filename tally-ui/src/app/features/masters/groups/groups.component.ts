import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { Subscription } from 'rxjs';

import { CompanyContextService } from '../../../core/api/company-context.service';
import { CacheExportApiService, ExportFormat } from '../../../core/api/cache-export-api.service';
import { GroupPayload, MastersApiService } from '../../../core/api/masters-api.service';
import { SyncMonitorService } from '../../../core/api/sync-monitor.service';

interface GroupRecord {
  name: string;
  parent: string;
  reservedName: string;
}

@Component({
  selector: 'app-groups',
  templateUrl: './groups.component.html',
  styleUrls: ['./groups.component.scss'],
})
export class GroupsComponent implements OnInit, OnDestroy {
  groups: GroupRecord[] = [];
  isLoading = true;
  isSubmitting = false;
  parentOptions: string[] = [];
  lastSyncedAt = '';
  responseSource = '';
  errorMessage = '';
  submitMessage = '';
  selectedCompany = '';
  selectedGroupName = '';
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

  get selectedGroup(): GroupRecord | null {
    return this.groups.find((group) => group.name === this.selectedGroupName) || null;
  }

  get detailTitle(): string {
    return this.createMode ? 'Group Creation' : 'Group Alteration';
  }

  get filteredGroups(): GroupRecord[] {
    const term = this.searchTerm.trim().toLowerCase();
    if (!term) {
      return this.groups;
    }
    return this.groups.filter((group) =>
      [group.name, group.parent, group.reservedName].some((value) => value.toLowerCase().includes(term))
    );
  }

  get primaryGroupCount(): number {
    return this.groups.filter((group) => !group.parent || group.parent.toLowerCase() === 'primary').length;
  }

  get customGroupCount(): number {
    return Math.max(this.groups.length - this.primaryGroupCount, 0);
  }

  load(): void {
    this.isLoading = true;
    this.errorMessage = '';
    this.mastersApiService.getGroups(this.selectedCompany).subscribe({
      next: (response) => {
        const rawGroups = this.toArray(response?.data ?? response?.GROUP);
        this.groups = rawGroups
          .map((item) => ({
            name: this.readText(item, 'NAME', 'name'),
            parent: this.readText(item, 'PARENT', 'parent'),
            reservedName: this.readText(item, 'RESERVEDNAME', 'reserved_name'),
          }))
          .filter((item) => item.name)
          .sort((left, right) => left.name.localeCompare(right.name));
        this.lastSyncedAt = this.asText(response?.meta?.last_synced_at);
        this.responseSource = this.asText(response?.meta?.source);
        this.parentOptions = this.groups.map((item) => item.name);
        const defaultParent = this.resolveDefaultParent();
        if (defaultParent) {
          this.form.patchValue({ PARENT: defaultParent });
        }
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
    const payload = this.form.getRawValue() as GroupPayload;

    this.mastersApiService.createGroup(payload, this.selectedCompany).subscribe({
      next: (response) => {
        this.isSubmitting = false;
        this.submitMessage = this.resolveSubmitMessage(payload.NAME, response);
        this.upsertLocalGroup(payload);
        this.selectGroup(payload.NAME);
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
    if (this.parentOptions.includes('Primary')) {
      return 'Primary';
    }
    return this.parentOptions[0] || '';
  }

  private resolveError(error: HttpErrorResponse): string {
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    return 'Unable to load or create groups from the backend.';
  }

  private resolveSubmitMessage(groupName: string, response: any): string {
    if (response?.queued === true) {
      const queueId = response?.queue_id ? ` Queue ID: ${response.queue_id}.` : '';
      return `Group "${groupName}" was saved in the backend database and is waiting for sync approval.${queueId}`;
    }
    return `Group "${groupName}" created successfully.`;
  }

  private upsertLocalGroup(payload: GroupPayload): void {
    const nextGroup: GroupRecord = {
      name: this.asText(payload.NAME),
      parent: this.asText(payload.PARENT),
      reservedName: '',
    };
    this.groups = [...this.groups.filter((item) => item.name.toLowerCase() !== nextGroup.name.toLowerCase()), nextGroup]
      .sort((left, right) => left.name.localeCompare(right.name));
    this.parentOptions = this.groups.map((item) => item.name);
    this.syncSelection(nextGroup.name);
  }

  selectGroup(name: string): void {
    this.createMode = false;
    this.selectedGroupName = name;
    const selectedGroup = this.groups.find((group) => group.name === name);
    this.form.reset({
      NAME: selectedGroup?.name || '',
      PARENT: selectedGroup?.parent || this.resolveDefaultParent(),
    });
  }

  openCreateMode(): void {
    this.createMode = true;
    this.selectedGroupName = '';
    this.submitMessage = '';
    this.errorMessage = '';
    this.form.reset({
      NAME: '',
      PARENT: this.resolveDefaultParent(),
    });
  }

  clearSearch(): void {
    this.searchTerm = '';
  }

  download(format: ExportFormat): void {
    this.cacheExportApiService.downloadAndSave('masters', 'groups', format, {
      company: this.selectedCompany || '',
    });
  }

  private syncSelection(preferredName?: string): void {
    if (this.createMode) {
      return;
    }

    const nextSelection =
      preferredName && this.groups.some((group) => group.name === preferredName)
        ? preferredName
        : this.groups.some((group) => group.name === this.selectedGroupName)
          ? this.selectedGroupName
          : this.groups[0]?.name || '';

    if (nextSelection) {
      this.selectGroup(nextSelection);
      return;
    }

    this.selectedGroupName = '';
    this.form.reset({
      NAME: '',
      PARENT: this.resolveDefaultParent(),
    });
  }
}
