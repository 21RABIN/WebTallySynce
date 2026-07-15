import { HttpErrorResponse } from '@angular/common/http';
import { Component } from '@angular/core';
import { FormBuilder } from '@angular/forms';

import { ApiWorkbenchService } from '../../../core/api/api-workbench.service';
import { CacheApiService } from '../../../core/api/cache-api.service';

type IntegrationMode =
  | 'routing-debug'
  | 'routing-select'
  | 'routing-clear'
  | 'xml-trace-status'
  | 'xml-trace-files'
  | 'xml-trace-file'
  | 'xml-access-steps'
  | 'xml-access-samples'
  | 'xml-access-check'
  | 'xml-execute'
  | 'single-sync'
  | 'validation'
  | 'xml-preview'
  | 'bulk-sync'
  | 'sync-logs'
  | 'sync-log'
  | 'sync-logs-entity'
  | 'sync-failed'
  | 'sync-retry'
  | 'sync-retry-failed'
  | 'sync-check'
  | 'sync-delete'
  | 'ledger-mappings'
  | 'ledger-mapping'
  | 'ledger-mapping-create'
  | 'ledger-mapping-update'
  | 'ledger-mapping-delete'
  | 'ledger-mapping-defaults';

type EntityOption = { label: string; value: string };

const SINGLE_SYNC_ENTITIES: EntityOption[] = [
  { label: 'Customer', value: 'customer' },
  { label: 'Supplier', value: 'supplier' },
  { label: 'Product', value: 'product' },
  { label: 'Sales Invoice', value: 'sales-invoice' },
  { label: 'Purchase Invoice', value: 'purchase-invoice' },
  { label: 'Payment', value: 'payment' },
  { label: 'Refund', value: 'refund' },
  { label: 'Cancel Invoice', value: 'cancel-invoice' },
  { label: 'Return Invoice', value: 'return-invoice' },
  { label: 'Stock Group', value: 'stock-group' },
  { label: 'Godown', value: 'godown' },
  { label: 'Budget', value: 'budget' },
  { label: 'Employee', value: 'employee' },
  { label: 'Sales Order', value: 'sales-order' },
  { label: 'Purchase Order', value: 'purchase-order' },
  { label: 'Delivery Note', value: 'delivery-note' },
  { label: 'Goods Receipt', value: 'goods-receipt' },
  { label: 'Contra', value: 'contra' },
  { label: 'Journal', value: 'journal' },
  { label: 'Payroll', value: 'payroll' },
  { label: 'Physical Stock', value: 'physical-stock' },
  { label: 'Attendance', value: 'attendance' },
];

const BULK_SYNC_ENTITIES: EntityOption[] = [
  { label: 'Customers', value: 'customers' },
  { label: 'Suppliers', value: 'suppliers' },
  { label: 'Products', value: 'products' },
  { label: 'Sales Invoices', value: 'sales-invoices' },
  { label: 'Purchase Invoices', value: 'purchase-invoices' },
  { label: 'Payments', value: 'payments' },
  { label: 'All Masters', value: 'all-masters' },
  { label: 'Stock Groups', value: 'stock-groups' },
  { label: 'Cost Centres', value: 'cost-centres' },
  { label: 'Voucher Types', value: 'voucher-types' },
  { label: 'Budgets', value: 'budgets' },
  { label: 'Employees', value: 'employees' },
  { label: 'Sales Orders', value: 'sales-orders' },
  { label: 'Purchase Orders', value: 'purchase-orders' },
  { label: 'Delivery Notes', value: 'delivery-notes' },
  { label: 'Goods Receipts', value: 'goods-receipts' },
  { label: 'Contras', value: 'contras' },
  { label: 'Journals', value: 'journals' },
  { label: 'Payroll', value: 'payroll' },
];

const INTEGRATION_MODES: Array<{ key: IntegrationMode; label: string; description: string }> = [
  { key: 'routing-debug', label: 'Routing Debug', description: 'Inspect current connector routing resolution.' },
  { key: 'routing-select', label: 'Select Connector', description: 'Select a connector by connector id, company, or branch.' },
  { key: 'routing-clear', label: 'Clear Connector Selection', description: 'Clear the current routing selection.' },
  { key: 'xml-trace-status', label: 'XML Trace Status', description: 'Load XML trace status from the backend.' },
  { key: 'xml-trace-files', label: 'XML Trace Files', description: 'Load available XML trace files.' },
  { key: 'xml-trace-file', label: 'XML Trace File', description: 'Load a single XML trace file by query parameter.' },
  { key: 'xml-access-steps', label: 'XML Access Steps', description: 'Load XML access guidance steps.' },
  { key: 'xml-access-samples', label: 'XML Access Samples', description: 'Load XML access samples.' },
  { key: 'xml-access-check', label: 'XML Access Check', description: 'Run XML access health checks.' },
  { key: 'xml-execute', label: 'XML Execute', description: 'Send raw XML or JSON payloads to the XML execution API.' },
  { key: 'single-sync', label: 'Single Entity Sync', description: 'Run a single sync request for a chosen entity type and id.' },
  { key: 'validation', label: 'Validation', description: 'Validate a single entity before sync.' },
  { key: 'xml-preview', label: 'XML Preview', description: 'Preview generated XML for an entity id.' },
  { key: 'bulk-sync', label: 'Bulk Sync', description: 'Run bulk sync actions through the bulk-sync controller.' },
  { key: 'sync-logs', label: 'Sync Logs', description: 'Load all sync log rows.' },
  { key: 'sync-log', label: 'Sync Log By Id', description: 'Load a sync log by its id.' },
  { key: 'sync-logs-entity', label: 'Sync Logs By Entity', description: 'Load logs for an entity type and entity id.' },
  { key: 'sync-failed', label: 'Failed Sync Logs', description: 'Load only failed sync log rows.' },
  { key: 'sync-retry', label: 'Retry Sync Log', description: 'Retry one failed sync log by id.' },
  { key: 'sync-retry-failed', label: 'Retry Failed Sync Logs', description: 'Retry all failed sync logs.' },
  { key: 'sync-check', label: 'Sync Check', description: 'Check sync status for an entity type and id.' },
  { key: 'sync-delete', label: 'Delete Sync Log', description: 'Delete a sync log by id.' },
  { key: 'ledger-mappings', label: 'Ledger Mappings', description: 'Load all Tally ledger mappings.' },
  { key: 'ledger-mapping', label: 'Ledger Mapping By Id', description: 'Load a single ledger mapping.' },
  { key: 'ledger-mapping-create', label: 'Create Ledger Mapping', description: 'Create a Tally ledger mapping record.' },
  { key: 'ledger-mapping-update', label: 'Update Ledger Mapping', description: 'Update a Tally ledger mapping record.' },
  { key: 'ledger-mapping-delete', label: 'Delete Ledger Mapping', description: 'Delete a Tally ledger mapping record.' },
  { key: 'ledger-mapping-defaults', label: 'Seed Ledger Mapping Defaults', description: 'Seed default mappings for a business unit.' },
];

@Component({
  selector: 'app-integration-studio',
  templateUrl: './integration-studio.component.html',
  styleUrls: ['./integration-studio.component.scss'],
})
export class IntegrationStudioComponent {
  readonly modes = INTEGRATION_MODES;
  readonly singleSyncEntities = SINGLE_SYNC_ENTITIES;
  readonly bulkSyncEntities = BULK_SYNC_ENTITIES;
  isSubmitting = false;
  errorMessage = '';
  responseBody = '';
  responseSummary = '';
  syncStatus: any = null;
  isRefreshingStatus = false;

  readonly form = this.formBuilder.group({
    mode: ['routing-debug' as IntegrationMode],
    connector_id: [''],
    company: [''],
    branch: [''],
    file_name: [''],
    entity_kind: [this.singleSyncEntities[0].value],
    bulk_kind: [this.bulkSyncEntities[0].value],
    entity_id: ['1'],
    log_id: ['1'],
    business_unit_id: ['1'],
    entity_type: ['customer'],
    force_sync: [false],
    payload: ['{\n  \n}'],
    xml_body: ['<ENVELOPE>\n  <HEADER></HEADER>\n  <BODY></BODY>\n</ENVELOPE>'],
  });

  constructor(
    private apiWorkbenchService: ApiWorkbenchService,
    private cacheApiService: CacheApiService,
    private formBuilder: FormBuilder
  ) {
    this.loadSyncStatus();
  }

  get selectedMode(): IntegrationMode {
    return (this.form.value.mode || 'routing-debug') as IntegrationMode;
  }

  get selectedModeLabel(): string {
    const match = this.modes.find((item) => item.key === this.selectedMode);
    return match ? match.label : 'Integration Mode';
  }

  get selectedModeDescription(): string {
    const match = this.modes.find((item) => item.key === this.selectedMode);
    return match ? match.description : '';
  }

  usesPayload(): boolean {
    return ['routing-select', 'xml-execute', 'bulk-sync', 'ledger-mapping-create', 'ledger-mapping-update'].includes(this.selectedMode);
  }

  usesXmlBody(): boolean {
    return this.selectedMode === 'xml-execute';
  }

  execute(): void {
    if (this.isSubmitting) {
      return;
    }

    this.isSubmitting = true;
    this.errorMessage = '';
    this.responseBody = '';
    this.responseSummary = '';

    this.buildRequest().subscribe({
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

  loadSyncStatus(): void {
    this.isRefreshingStatus = true;
    this.cacheApiService.getStatus().subscribe({
      next: (response) => {
        this.syncStatus = response;
        this.isRefreshingStatus = false;
      },
      error: () => {
        this.isRefreshingStatus = false;
      },
    });
  }

  runCacheSync(): void {
    if (this.isSubmitting) {
      return;
    }

    this.isSubmitting = true;
    this.errorMessage = '';
    this.cacheApiService.runSync().subscribe({
      next: (response) => {
        this.responseBody = this.formatResponse(response);
        this.responseSummary = this.describeResponse(response);
        this.isSubmitting = false;
        this.loadSyncStatus();
      },
      error: (error: HttpErrorResponse) => {
        this.errorMessage = this.resolveError(error);
        this.isSubmitting = false;
      },
    });
  }

  private buildRequest() {
    const value = this.form.getRawValue();
    switch (this.selectedMode) {
      case 'routing-debug':
        return this.apiWorkbenchService.get('/api/routing/debug');
      case 'routing-select':
        return this.apiWorkbenchService.post('/api/routing/connector', {
          connector_id: value.connector_id,
          company: value.company,
          branch: value.branch,
        }, { contentType: 'application/json' });
      case 'routing-clear':
        return this.apiWorkbenchService.delete('/api/routing/connector');
      case 'xml-trace-status':
        return this.apiWorkbenchService.get('/api/xml/trace/status');
      case 'xml-trace-files':
        return this.apiWorkbenchService.get('/api/xml/trace/files');
      case 'xml-trace-file':
        return this.apiWorkbenchService.get('/api/xml/trace/file', { file: value.file_name });
      case 'xml-access-steps':
        return this.apiWorkbenchService.get('/api/xml/access/steps');
      case 'xml-access-samples':
        return this.apiWorkbenchService.get('/api/xml/access/samples');
      case 'xml-access-check':
        return this.apiWorkbenchService.get('/api/xml/access/check');
      case 'xml-execute':
        return this.apiWorkbenchService.post('/api/xml/execute', value.xml_body || '', { contentType: 'application/xml' });
      case 'single-sync':
        return this.apiWorkbenchService.post(`/api/sync/${value.entity_kind}/${value.entity_id}`, null, {
          params: { forceSync: value.force_sync ? 'true' : 'false' },
        });
      case 'validation':
        return this.apiWorkbenchService.post(`/api/tally/validate/${value.entity_kind}/${value.entity_id}`);
      case 'xml-preview':
        return this.apiWorkbenchService.get(`/api/tally/xml-preview/${value.entity_kind}/${value.entity_id}`);
      case 'bulk-sync':
        return this.apiWorkbenchService.post(`/api/tally/bulk-sync/${value.bulk_kind}`, value.payload || '{}', { contentType: 'application/json' });
      case 'sync-logs':
        return this.apiWorkbenchService.get('/api/tally-sync/logs');
      case 'sync-log':
        return this.apiWorkbenchService.get(`/api/tally-sync/logs/${value.log_id}`);
      case 'sync-logs-entity':
        return this.apiWorkbenchService.get(`/api/tally-sync/logs/entity/${value.entity_type}/${value.entity_id}`);
      case 'sync-failed':
        return this.apiWorkbenchService.get('/api/tally-sync/failed');
      case 'sync-retry':
        return this.apiWorkbenchService.post(`/api/tally-sync/retry/${value.log_id}`);
      case 'sync-retry-failed':
        return this.apiWorkbenchService.post('/api/tally-sync/retry-failed');
      case 'sync-check':
        return this.apiWorkbenchService.get(`/api/tally-sync/check/${value.entity_type}/${value.entity_id}`);
      case 'sync-delete':
        return this.apiWorkbenchService.delete(`/api/tally-sync/logs/${value.log_id}`);
      case 'ledger-mappings':
        return this.apiWorkbenchService.get('/api/tally-mappings/ledgers');
      case 'ledger-mapping':
        return this.apiWorkbenchService.get(`/api/tally-mappings/ledgers/${value.entity_id}`);
      case 'ledger-mapping-create':
        return this.apiWorkbenchService.post('/api/tally-mappings/ledgers', value.payload || '{}', { contentType: 'application/json' });
      case 'ledger-mapping-update':
        return this.apiWorkbenchService.put(`/api/tally-mappings/ledgers/${value.entity_id}`, value.payload || '{}', { contentType: 'application/json' });
      case 'ledger-mapping-delete':
        return this.apiWorkbenchService.delete(`/api/tally-mappings/ledgers/${value.entity_id}`);
      case 'ledger-mapping-defaults':
        return this.apiWorkbenchService.post(`/api/tally-mappings/ledgers/defaults/${value.business_unit_id}`);
      default:
        return this.apiWorkbenchService.get('/api/routing/debug');
    }
  }

  private describeResponse(response: any): string {
    try {
      const parsed = typeof response === 'string' ? (JSON.parse(response) as unknown) : (response as unknown);
      if (Array.isArray(parsed)) {
        return `${parsed.length} item(s) returned from ${this.selectedModeLabel}.`;
      }
      if (parsed && typeof parsed === 'object') {
        return `${Object.keys(parsed as Record<string, unknown>).length} top-level field(s) returned from ${this.selectedModeLabel}.`;
      }
    } catch {
      return `Raw response returned from ${this.selectedModeLabel}.`;
    }
    return `Response returned from ${this.selectedModeLabel}.`;
  }

  private formatResponse(response: any): string {
    try {
      return typeof response === 'string'
        ? JSON.stringify(JSON.parse(response), null, 2)
        : JSON.stringify(response, null, 2);
    } catch {
      return typeof response === 'string' ? response || 'No response body returned.' : 'No response body returned.';
    }
  }

  private resolveError(error: HttpErrorResponse): string {
    if (typeof error.error === 'string' && error.error.trim()) {
      return error.error;
    }
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    return 'Unable to execute the selected integration API.';
  }
}
