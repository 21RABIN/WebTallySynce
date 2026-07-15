import { HttpErrorResponse } from '@angular/common/http';
import { Component } from '@angular/core';
import { FormBuilder } from '@angular/forms';

import { ApiWorkbenchService } from '../../../core/api/api-workbench.service';

type BusinessApiDefinition = {
  key: string;
  label: string;
  path: string;
  method: 'GET' | 'POST';
  description: string;
  category: 'Settings' | 'Companies' | 'Company Context';
  contentType?: string;
  samplePayload?: string;
};

const BUSINESS_APIS: BusinessApiDefinition[] = [
  { key: 'company-features-get', label: 'Company Features', path: '/api/cache/settings/company-features', method: 'GET', category: 'Settings', description: 'Load company feature configuration from the database-backed cache.' },
  { key: 'company-features-post', label: 'Update Company Features', path: '/api/settings/company-features', method: 'POST', category: 'Settings', description: 'Save company feature configuration.', contentType: 'application/json', samplePayload: '{\n  "company": "Demo Company",\n  "feature": "Enable GST"\n}' },
  { key: 'gst-registration-get', label: 'GST Registration', path: '/api/cache/settings/gst-registration', method: 'GET', category: 'Settings', description: 'Load GST registration settings from the database-backed cache.' },
  { key: 'gst-registration-post', label: 'Update GST Registration', path: '/api/settings/gst-registration', method: 'POST', category: 'Settings', description: 'Save GST registration settings.', contentType: 'application/json', samplePayload: '{\n  "gstin": "29ABCDE1234F2Z5"\n}' },
  { key: 'company-currency-get', label: 'Company Currency', path: '/api/cache/settings/company-currency', method: 'GET', category: 'Settings', description: 'Load company currency settings from the database-backed cache.' },
  { key: 'company-currency-post', label: 'Update Company Currency', path: '/api/settings/company-currency', method: 'POST', category: 'Settings', description: 'Save company currency settings.', contentType: 'application/json', samplePayload: '{\n  "currency": "INR"\n}' },
  { key: 'numbering-rules-get', label: 'Numbering Rules', path: '/api/cache/settings/numbering-rules', method: 'GET', category: 'Settings', description: 'Load voucher numbering rule settings from the database-backed cache.' },
  { key: 'numbering-rules-post', label: 'Update Numbering Rules', path: '/api/settings/numbering-rules', method: 'POST', category: 'Settings', description: 'Save voucher numbering rule settings.', contentType: 'application/json', samplePayload: '{\n  "voucherType": "Sales",\n  "prefix": "SAL"\n}' },
  { key: 'tax-rate-tables-get', label: 'Tax Rate Tables', path: '/api/cache/settings/tax-rate-tables', method: 'GET', category: 'Settings', description: 'Load tax rate tables from the database-backed cache.' },
  { key: 'price-structures-get', label: 'Price Structures', path: '/api/cache/settings/price-structures', method: 'GET', category: 'Settings', description: 'Load price structure setup from the database-backed cache.' },
  { key: 'price-structures-post', label: 'Update Price Structures', path: '/api/settings/price-structures', method: 'POST', category: 'Settings', description: 'Save price structure setup.', contentType: 'application/json', samplePayload: '{\n  "priceLevel": "Retail"\n}' },
  { key: 'stock-controls-get', label: 'Stock Controls', path: '/api/cache/settings/stock-controls', method: 'GET', category: 'Settings', description: 'Load stock control settings from the database-backed cache.' },
  { key: 'stock-controls-post', label: 'Update Stock Controls', path: '/api/settings/stock-controls', method: 'POST', category: 'Settings', description: 'Save stock control settings.', contentType: 'application/json', samplePayload: '{\n  "allowNegativeStock": false\n}' },
  { key: 'security-roles-get', label: 'Security Roles', path: '/api/cache/settings/security-roles', method: 'GET', category: 'Settings', description: 'Load security roles and permissions from the database-backed cache.' },
  { key: 'security-roles-post', label: 'Update Security Roles', path: '/api/settings/security-roles', method: 'POST', category: 'Settings', description: 'Save security roles and permissions.', contentType: 'application/json', samplePayload: '{\n  "role": "Finance Operator"\n}' },
  { key: 'uqc-mappings-get', label: 'UQC Mappings', path: '/api/cache/settings/uqc-mappings', method: 'GET', category: 'Settings', description: 'Load UQC mapping definitions from the database-backed cache.' },
  { key: 'einvoice-get', label: 'E-Invoice Settings', path: '/api/cache/settings/einvoice', method: 'GET', category: 'Settings', description: 'Load e-invoice settings from the database-backed cache.' },
  { key: 'einvoice-post', label: 'Update E-Invoice Settings', path: '/api/settings/einvoice', method: 'POST', category: 'Settings', description: 'Save e-invoice settings.', contentType: 'application/json', samplePayload: '{\n  "enabled": true\n}' },
  { key: 'ewaybill-get', label: 'E-Way Bill Settings', path: '/api/cache/settings/ewaybill', method: 'GET', category: 'Settings', description: 'Load e-way bill settings from the database-backed cache.' },
  { key: 'ewaybill-post', label: 'Update E-Way Bill Settings', path: '/api/settings/ewaybill', method: 'POST', category: 'Settings', description: 'Save e-way bill settings.', contentType: 'application/json', samplePayload: '{\n  "enabled": true\n}' },
  { key: 'companies-get', label: 'Companies', path: '/api/cache/masters/companies', method: 'GET', category: 'Companies', description: 'Load company data from the database-backed cache.' },
  { key: 'companies-post', label: 'Companies Upsert', path: '/api/companies', method: 'POST', category: 'Companies', description: 'Upsert company data through the generic companies endpoint.', contentType: 'application/json', samplePayload: '{\n  "name": "Demo Company"\n}' },
  { key: 'companies-list', label: 'Company List', path: '/api/companies/list', method: 'GET', category: 'Companies', description: 'Load the available company list.' },
  { key: 'companies-open', label: 'Open Company', path: '/api/companies/open', method: 'POST', category: 'Companies', description: 'Open or switch to a company.', contentType: 'application/json', samplePayload: '{\n  "company": "Demo Company"\n}' },
  { key: 'companies-update', label: 'Update Company', path: '/api/companies/update', method: 'POST', category: 'Companies', description: 'Update an existing company.', contentType: 'application/json', samplePayload: '{\n  "company": "Demo Company"\n}' },
  { key: 'companies-create', label: 'Create Company', path: '/api/companies/create', method: 'POST', category: 'Companies', description: 'Create a new company.', contentType: 'application/json', samplePayload: '{\n  "name": "New Company"\n}' },
  { key: 'companies-alter', label: 'Alter Company', path: '/api/companies/alter', method: 'POST', category: 'Companies', description: 'Alter a company through the connector route.', contentType: 'application/json', samplePayload: '{\n  "company": "Demo Company"\n}' },
  { key: 'company-active', label: 'Active Company Context', path: '/api/tally/company/active', method: 'GET', category: 'Company Context', description: 'Load the active Tally company context.' },
  { key: 'company-select', label: 'Select Company Context', path: '/api/tally/company/select', method: 'POST', category: 'Company Context', description: 'Select the active Tally company context.', contentType: 'application/json', samplePayload: '{\n  "companyName": "Demo Company"\n}' },
  { key: 'company-context-features', label: 'Company Context Features', path: '/api/tally/company/features', method: 'GET', category: 'Company Context', description: 'Load feature data for the active company context.' },
  { key: 'company-context-gst', label: 'Company Context GST Details', path: '/api/tally/company/gst-details', method: 'GET', category: 'Company Context', description: 'Load GST details for the active company context.' },
];

@Component({
  selector: 'app-business-config-studio',
  templateUrl: './business-config-studio.component.html',
  styleUrls: ['./business-config-studio.component.scss'],
})
export class BusinessConfigStudioComponent {
  readonly apis = BUSINESS_APIS;
  isSubmitting = false;
  errorMessage = '';
  responseBody = '';
  responseSummary = '';

  readonly form = this.formBuilder.group({
    api: [this.apis[0].key],
    payload: [this.apis[0].samplePayload || '{\n  \n}'],
  });

  constructor(private apiWorkbenchService: ApiWorkbenchService, private formBuilder: FormBuilder) {}

  get selectedApi(): BusinessApiDefinition {
    const selected = this.apis.find((item) => item.key === this.form.value.api);
    return selected || this.apis[0];
  }

  onApiChange(): void {
    this.form.patchValue({
      payload: this.selectedApi.samplePayload || '{\n  \n}',
    });
  }

  execute(): void {
    if (this.isSubmitting) {
      return;
    }
    this.isSubmitting = true;
    this.errorMessage = '';
    this.responseBody = '';
    this.responseSummary = '';

    const request$ = this.selectedApi.method === 'GET'
      ? this.apiWorkbenchService.get(this.selectedApi.path)
      : this.apiWorkbenchService.post(
          this.selectedApi.path,
          this.form.value.payload || '{}',
          { contentType: this.selectedApi.contentType || 'application/json' }
        );

    request$.subscribe({
      next: (response) => {
        this.responseBody = this.formatResponse(response);
        this.responseSummary = this.describeResponse(response);
        this.isSubmitting = false;
      },
      error: (error: HttpErrorResponse) => {
        this.errorMessage = this.resolveError(error);
        this.isSubmitting = false;
      },
    });
  }

  private describeResponse(response: string): string {
    try {
      const parsed = JSON.parse(response) as unknown;
      if (Array.isArray(parsed)) {
        return `${parsed.length} item(s) returned from ${this.selectedApi.label}.`;
      }
      if (parsed && typeof parsed === 'object') {
        return `${Object.keys(parsed as Record<string, unknown>).length} top-level field(s) returned from ${this.selectedApi.label}.`;
      }
    } catch {
      return `Raw response returned from ${this.selectedApi.label}.`;
    }
    return `Response returned from ${this.selectedApi.label}.`;
  }

  private formatResponse(response: string): string {
    try {
      return JSON.stringify(JSON.parse(response), null, 2);
    } catch {
      return response || 'No response body returned.';
    }
  }

  private resolveError(error: HttpErrorResponse): string {
    if (typeof error.error === 'string' && error.error.trim()) {
      return error.error;
    }
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    return 'Unable to execute the selected business configuration API.';
  }
}
