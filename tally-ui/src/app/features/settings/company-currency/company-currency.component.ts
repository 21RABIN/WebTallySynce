import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder } from '@angular/forms';
import { Subscription } from 'rxjs';

import { CompanyContextService } from '../../../core/api/company-context.service';
import { SettingsApiService } from '../../../core/api/settings-api.service';

@Component({
  selector: 'app-company-currency',
  templateUrl: './company-currency.component.html',
  styleUrls: ['./company-currency.component.scss'],
})
export class CompanyCurrencyComponent implements OnInit, OnDestroy {
  isLoading = true;
  isSubmitting = false;
  errorMessage = '';
  submitMessage = '';
  response: any = null;
  selectedCompany = '';

  readonly form = this.formBuilder.group({
    CURRENCYNAME: [''],
    MAILINGNAME: [''],
    DECIMALSYMBOL: [''],
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
    this.settingsApiService.getCompanyCurrency(this.selectedCompany).subscribe({
      next: (response) => {
        this.response = response;
        const company = this.primaryCompany;
        this.form.patchValue({
          CURRENCYNAME: this.readValue(company.CURRENCYNAME),
          MAILINGNAME: this.readValue(company.MAILINGNAME),
          DECIMALSYMBOL: this.readValue(company.DECIMALSYMBOL),
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

  get booksFrom(): string {
    return this.readValue(this.primaryCompany.BOOKSFROM) || '-';
  }

  get summaryCards(): Array<{ label: string; value: string }> {
    return [
      { label: 'Editing Company', value: this.companyName },
      { label: 'Mailing Currency', value: this.readValue(this.primaryCompany.MAILINGNAME) || '-' },
      { label: 'Currency Name', value: this.readValue(this.primaryCompany.CURRENCYNAME) || 'Not provided' },
      { label: 'Decimal Symbol', value: this.readValue(this.primaryCompany.DECIMALSYMBOL) || '-' },
    ];
  }

  save(): void {
    if (this.isSubmitting) {
      return;
    }
    this.isSubmitting = true;
    this.submitMessage = '';
    this.errorMessage = '';
    this.settingsApiService.updateCompanyCurrency(this.form.getRawValue(), this.selectedCompany).subscribe({
      next: (response) => {
        this.isSubmitting = false;
        this.submitMessage = this.resolveSubmitMessage(response);
        this.applyLocalCurrencyState();
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
    return 'Unable to load or save company currency settings.';
  }

  private resolveSubmitMessage(response: any): string {
    if (response?.queued === true) {
      const queueId = response?.queue_id ? ` Queue ID: ${response.queue_id}.` : '';
      return `Company currency settings were saved in the backend database and are waiting for sync approval.${queueId}`;
    }
    return 'Company currency settings saved.';
  }

  private applyLocalCurrencyState(): void {
    const primaryCompany = {
      ...this.primaryCompany,
      CURRENCYNAME: this.readValue(this.form.get('CURRENCYNAME')?.value),
      MAILINGNAME: this.readValue(this.form.get('MAILINGNAME')?.value),
      DECIMALSYMBOL: this.readValue(this.form.get('DECIMALSYMBOL')?.value),
    };
    const remainingCompanies = this.companies.slice(0, Math.max(this.companies.length - 1, 0));
    this.response = {
      ...(this.response || {}),
      data: [...remainingCompanies, primaryCompany],
    };
  }
}
