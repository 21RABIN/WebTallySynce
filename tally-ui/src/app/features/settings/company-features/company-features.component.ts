import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder } from '@angular/forms';
import { Subscription } from 'rxjs';

import { CompanyContextService } from '../../../core/api/company-context.service';
import { SettingsApiService } from '../../../core/api/settings-api.service';

@Component({
  selector: 'app-company-features',
  templateUrl: './company-features.component.html',
  styleUrls: ['./company-features.component.scss'],
})
export class CompanyFeaturesComponent implements OnInit, OnDestroy {
  isLoading = true;
  isSubmitting = false;
  errorMessage = '';
  submitMessage = '';
  response: any = null;
  selectedCompany = '';

  readonly form = this.formBuilder.group({
    EMAIL: [''],
    PINCODE: [''],
    COUNTRYNAME: [''],
    STATENAME: [''],
    ISINVENTORYON: [''],
    ISGSTON: [''],
  });
  private readonly subscriptions = new Subscription();

  constructor(
    private settingsApiService: SettingsApiService,
    private formBuilder: FormBuilder,
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
    this.load();
    this.companyContextService.refreshCompanies();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  load(): void {
    this.isLoading = true;
    this.errorMessage = '';
    this.settingsApiService.getCompanyFeatures(this.selectedCompany).subscribe({
      next: (response) => {
        this.response = response;
        const company = this.primaryCompany;
        this.form.patchValue({
          EMAIL: this.readValue(company.EMAIL),
          PINCODE: this.readValue(company.PINCODE),
          COUNTRYNAME: this.readValue(company.COUNTRYNAME),
          STATENAME: this.readValue(company.STATENAME),
          ISINVENTORYON: this.readValue(company.ISINVENTORYON),
          ISGSTON: this.readValue(company.ISGSTON),
        });
        this.isLoading = false;
      },
      error: (error: HttpErrorResponse) => {
        this.errorMessage = this.resolveError(error);
        this.isLoading = false;
      },
    });
  }

  get companies(): Array<Record<string, unknown>> {
    return this.toArray<Record<string, unknown>>(this.response?.data ?? this.response?.COMPANY);
  }

  get primaryCompany(): Record<string, unknown> {
    return this.companies[this.companies.length - 1] || {};
  }

  get companyName(): string {
    return this.readValue(this.primaryCompany.NAME) || 'Current Company';
  }

  get featureCards(): Array<{ label: string; value: string }> {
    return [
      { label: 'Editing Company', value: this.companyName },
      { label: 'Country', value: this.readValue(this.primaryCompany.COUNTRYNAME) || '-' },
      { label: 'State', value: this.readValue(this.primaryCompany.STATENAME) || '-' },
      { label: 'Inventory', value: this.readValue(this.primaryCompany.ISINVENTORYON) || '-' },
      { label: 'GST', value: this.readValue(this.primaryCompany.ISGSTON) || '-' },
      { label: 'Pincode', value: this.readValue(this.primaryCompany.PINCODE) || '-' },
    ];
  }

  save(): void {
    if (this.isSubmitting) {
      return;
    }
    this.isSubmitting = true;
    this.submitMessage = '';
    this.errorMessage = '';
    this.settingsApiService.updateCompanyFeatures(this.form.getRawValue(), this.selectedCompany).subscribe({
      next: (response) => {
        this.isSubmitting = false;
        this.submitMessage = this.resolveSubmitMessage(response);
        this.applyLocalFeatureState();
        this.load();
      },
      error: (error: HttpErrorResponse) => {
        this.isSubmitting = false;
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

  readValue(value: unknown): string {
    if (typeof value === 'string') {
      return value;
    }
    if (Array.isArray(value)) {
      return value.find((item) => typeof item === 'string') || '';
    }
    return '';
  }

  private resolveError(error: HttpErrorResponse): string {
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    return 'Unable to load or save company feature settings.';
  }

  private resolveSubmitMessage(response: any): string {
    if (response?.queued === true) {
      const queueId = response?.queue_id ? ` Queue ID: ${response.queue_id}.` : '';
      return `Company feature settings were saved in the backend database and are waiting for sync approval.${queueId}`;
    }
    return 'Company feature settings saved.';
  }

  private applyLocalFeatureState(): void {
    const primaryCompany = {
      ...this.primaryCompany,
      EMAIL: this.readValue(this.form.get('EMAIL')?.value),
      PINCODE: this.readValue(this.form.get('PINCODE')?.value),
      COUNTRYNAME: this.readValue(this.form.get('COUNTRYNAME')?.value),
      STATENAME: this.readValue(this.form.get('STATENAME')?.value),
      ISINVENTORYON: this.readValue(this.form.get('ISINVENTORYON')?.value),
      ISGSTON: this.readValue(this.form.get('ISGSTON')?.value),
    };
    const remainingCompanies = this.companies.slice(0, Math.max(this.companies.length - 1, 0));
    this.response = {
      ...(this.response || {}),
      data: [...remainingCompanies, primaryCompany],
    };
  }
}
