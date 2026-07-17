import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { Subscription, forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { CompanyContextService } from '../../../core/api/company-context.service';
import { MastersApiService } from '../../../core/api/masters-api.service';
import { ReportsApiService } from '../../../core/api/reports-api.service';
import { SettingsApiService } from '../../../core/api/settings-api.service';
import { SyncMonitorService } from '../../../core/api/sync-monitor.service';
import {
  VoucherComplianceStatusRequest,
  VoucherEndpointOption,
  VOUCHER_ENDPOINTS,
  VouchersApiService,
} from '../../../core/api/vouchers-api.service';

interface UiField {
  label: string;
  value: string;
}

interface UiSection {
  title: string;
  fields: UiField[];
}

interface UiMetric {
  label: string;
  value: string;
  tone?: 'default' | 'success' | 'accent';
}

interface UiTimelineStep {
  label: string;
  status: 'done' | 'active' | 'pending';
  detail: string;
}

type ComplianceView = 'generate' | 'ready' | 'status';

interface StockItemOption {
  name: string;
  baseUnits: string;
  hsnCode: string;
  quantity: string;
  rate: string;
  value: string;
}

interface InventoryLine {
  stockItemName: string;
  description: string;
  hsnCode: string;
  quantity: string;
  unit: string;
  rate: string;
  gstRate: string;
  discount: string;
  amount: string;
}

@Component({
  selector: 'app-voucher-create',
  templateUrl: './voucher-create.component.html',
  styleUrls: ['./voucher-create.component.scss'],
})
export class VoucherCreateComponent implements OnInit, OnDestroy {
  private currentCompanyName = '';
  private currentVoucherDate = this.formatVoucherDate(new Date());
  private latestAcceptedVoucherDate = this.currentVoucherDate;
  private lookupPanelCloseHandle: ReturnType<typeof setTimeout> | null = null;
  private ledgerLoadRequestId = 0;
  private stockItemLoadRequestId = 0;
  private voucherNumberLoadRequestId = 0;
  private lastAutoVoucherNumber = '';
  readonly endpoints = VOUCHER_ENDPOINTS.filter((endpoint) => endpoint.key !== 'vouchers');
  readonly statusEndpointOptions = [
    { key: 'sales-einvoice-status', label: 'Sales E-Invoice Status', path: '/api/vouchers/sales/einvoice-status' },
    { key: 'sales-ewaybill-status', label: 'Sales E-Way Bill Status', path: '/api/vouchers/sales/ewaybill-status' },
  ];
  ledgerOptions: string[] = [];
  stockItemOptions: StockItemOption[] = [];
  stockItemNameOptions: string[] = [];
  activeComplianceView: ComplianceView = 'generate';
  isLoadingLedgers = false;
  isLoadingStockItems = false;
  isSubmitting = false;
  isCheckingStatus = false;
  isRunningCompliance = false;
  errorMessage = '';
  submitMessage = '';
  responseText = '';
  complianceMessage = '';
  complianceErrorMessage = '';
  complianceResponseText = '';
  statusErrorMessage = '';
  statusResponseText = '';
  companyOptions: string[] = [];
  inventoryLines: InventoryLine[] = [];
  activeInventoryLineIndex = 0;
  openStockItemLineIndex: number | null = null;
  activeLookupPanel: 'partyLedgerName' | 'offsetLedgerName' | 'stockItemName' | null = null;
  activeLookupIndex = -1;
  private readonly subscriptions = new Subscription();

  readonly form = this.formBuilder.group({
    endpointKey: [this.endpoints[0].key, Validators.required],
    action: ['Create'],
    company: [this.currentCompanyName, Validators.required],
    date: [this.toDateInputValue(this.currentVoucherDate), Validators.required],
    voucherTypeName: [this.endpoints[0].defaultVoucherType, Validators.required],
    voucherNumber: ['', Validators.required],
    partyLedgerName: ['Cash'],
    offsetLedgerName: ['Bank'],
    narration: ['Created from tally-ui'],
    invoiceNumber: [''],
    amount: ['0.00'],
    sellerGstin: ['29ABCDE1234F1Z5'],
    sellerLegalName: ['Rabin Tally Services'],
    sellerAddress1: ['12 Market Road'],
    sellerAddress2: ['Near Finance Circle'],
    sellerLocation: ['Bengaluru'],
    sellerPincode: ['560001'],
    sellerStateCode: ['29'],
    sellerPhone: ['9876543210'],
    sellerEmail: ['accounts@example.com'],
    buyerGstin: ['29AAACB2894G1ZJ'],
    buyerLegalName: ['Customer 1'],
    buyerTradeName: ['Customer 1'],
    buyerAddress1: ['22 Client Street'],
    buyerAddress2: ['Industrial Layout'],
    buyerLocation: ['Bengaluru'],
    buyerPincode: ['560048'],
    buyerStateCode: ['29'],
    buyerPlaceOfSupply: ['29'],
    buyerEmail: ['buyer@example.com'],
    buyerPhone: ['9123456780'],
    stockItemName: [''],
    itemDescription: [''],
    itemHsnCode: [''],
    itemQuantity: ['1'],
    itemUnit: ['NOS'],
    itemRate: ['0.00'],
    itemTaxableAmount: ['0.00'],
    itemGstRate: ['18'],
    itemDiscount: ['0'],
    itemOtherCharge: ['0'],
    freightLedgerName: ['Freight Charges'],
    freightAmount: ['0.00'],
    roundOffLedgerName: ['Round Off'],
    roundOffAmount: ['0.00'],
    taxLedgerName: ['Output CGST/SGST'],
    transporterId: ['29TRANS1234A1Z5'],
    transporterName: ['Fast Move Logistics'],
    transportMode: ['Road'],
    distanceKm: ['120'],
    vehicleNumber: ['KA01AB1234'],
    vehicleType: ['Regular'],
    transportDocumentNo: ['LR-1001'],
    transportDocumentDate: ['2026-04-02'],
    dispatchAddress1: ['12 Market Road'],
    dispatchAddress2: ['Near Finance Circle'],
    dispatchLocation: ['Bengaluru'],
    dispatchPincode: ['560001'],
    dispatchStateCode: ['29'],
    shipAddress1: ['22 Client Street'],
    shipAddress2: ['Industrial Layout'],
    shipLocation: ['Bengaluru'],
    shipPincode: ['560048'],
    shipStateCode: ['29'],
    rawXml: [''],
  });

  readonly statusForm = this.formBuilder.group({
    endpointKey: [this.statusEndpointOptions[0].key, Validators.required],
    company: [this.currentCompanyName, Validators.required],
    voucherNumber: ['SI-1001'],
    reference: [''],
    partyLedgerName: ['Customer 1'],
    fromDate: [this.toDateInputValue(this.currentVoucherDate)],
    toDate: [this.toDateInputValue(this.currentVoucherDate)],
    limit: ['20'],
  });

  constructor(
    private formBuilder: FormBuilder,
    private vouchersApiService: VouchersApiService,
    private reportsApiService: ReportsApiService,
    private mastersApiService: MastersApiService,
    private settingsApiService: SettingsApiService,
    private companyContextService: CompanyContextService,
    private syncMonitorService: SyncMonitorService
  ) {}

  ngOnInit(): void {
    this.inventoryLines = [this.createEmptyInventoryLine()];
    this.subscriptions.add(this.companyContextService.companies$.subscribe((companies) => {
      this.companyOptions = companies;
      if (companies.length && (!this.ledgerOptions.length || !this.stockItemOptions.length)) {
        this.loadMasterOptions();
      }
    }));
    this.subscriptions.add(this.companyContextService.selectedCompany$.subscribe((company) => {
      const nextCompany = company || '';
      if (nextCompany === this.currentCompanyName) {
        return;
      }
      this.currentCompanyName = nextCompany;
      this.form.patchValue({
        company: nextCompany,
        date: this.toDateInputValue(this.currentVoucherDate),
      });
      this.statusForm.patchValue({
        company: nextCompany,
        fromDate: this.toDateInputValue(this.currentVoucherDate),
        toDate: this.toDateInputValue(this.currentVoucherDate),
      });
      this.loadMasterOptions();
      this.applyEndpointPreset(this.form.controls.endpointKey.value);
      this.loadLatestAcceptedVoucherDate(nextCompany);
      this.refreshAutoVoucherNumber();
    }));
    this.subscriptions.add(this.syncMonitorService.refreshRequested$.subscribe(() => {
      this.loadMasterOptions();
    }));
    this.loadMasterOptions();
    this.applyEndpointPreset(this.form.controls.endpointKey.value);
    this.companyContextService.refreshCompanies();
    this.refreshAutoVoucherNumber();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  get selectedEndpoint(): VoucherEndpointOption {
    const endpointKey = this.form.controls.endpointKey.value;
    return this.endpoints.find((item) => item.key === endpointKey) || this.endpoints[0];
  }

  get selectedStatusEndpoint(): { key: string; label: string; path: string } {
    const endpointKey = this.statusForm.controls.endpointKey.value;
    return this.statusEndpointOptions.find((item) => item.key === endpointKey) || this.statusEndpointOptions[0];
  }

  get voucherEndpointOptions(): VoucherEndpointOption[] {
    return this.endpoints.filter((item) => item.requestKind === 'voucher');
  }

  get companyLabel(): string {
    return this.form.controls.company.value || this.currentCompanyName || '';
  }

  get isVoucherWorkspace(): boolean {
    return this.selectedEndpoint.requestKind === 'voucher';
  }

  get selectedVoucherHeading(): string {
    return this.form.controls.voucherTypeName.value || this.selectedEndpoint.defaultVoucherType || 'Voucher';
  }

  get voucherNumberInlineError(): string {
    const control = this.form.controls.voucherNumber;
    if (!control.touched || !control.hasError('duplicate')) {
      return '';
    }
    return this.errorMessage || 'This voucher number already exists. Use the suggested next voucher number.';
  }

  get partyLedgerLabel(): string {
    const normalizedType = (this.form.controls.voucherTypeName.value || '').trim().toLowerCase();
    return normalizedType === 'purchase' || normalizedType === 'purchase order' ? 'Supplier A/c' : 'Party A/c';
  }

  get offsetLedgerLabel(): string {
    const normalizedType = (this.form.controls.voucherTypeName.value || '').trim().toLowerCase();
    return normalizedType === 'purchase' || normalizedType === 'purchase order' ? 'Purchase Ledger' : 'Sales Ledger';
  }

  get voucherTotalAmount(): string {
    if (this.showInventoryVoucherSection) {
      return this.inventorySubtotal;
    }
    return this.form.controls.amount.value || '0.00';
  }

  get canRunSalesCompliance(): boolean {
    return this.selectedEndpoint.key === 'sales' && !!this.responseText.trim() && !this.errorMessage;
  }

  get showSalesComplianceStudio(): boolean {
    return this.selectedEndpoint.key === 'sales';
  }

  get selectedStockUnitLabel(): string {
    return this.currentInventoryLine?.unit || this.form.controls.itemUnit.value || this.resolveStockItemBaseUnits(this.form.controls.stockItemName.value || '') || 'NOS';
  }

  get selectedStockItemOption(): StockItemOption | null {
    const selectedName = this.currentInventoryLine?.stockItemName || this.form.controls.stockItemName.value || '';
    return this.stockItemOptions.find((item) => item.name === selectedName) || null;
  }

  get selectedStockAvailabilityLabel(): string {
    return this.formatStockAvailabilityLabel(this.selectedStockItemOption);
  }

  get selectedItemHsnDisplay(): string {
    return this.currentInventoryLine?.hsnCode || this.form.controls.itemHsnCode.value || this.selectedStockItemOption?.hsnCode || 'Pending';
  }

  get selectedPartyDisplay(): string {
    return this.form.controls.partyLedgerName.value || 'Pending';
  }

  get filteredPartyLedgerOptions(): string[] {
    return this.ledgerOptions;
  }

  get filteredOffsetLedgerOptions(): string[] {
    return this.ledgerOptions;
  }

  get filteredStockItemOptions(): StockItemOption[] {
    return this.stockItemOptions;
  }

  get stockItemSelectOptions(): string[] {
    return this.stockItemOptions.map((item) => item.name);
  }

  getInventoryLineStockItemValue(line: InventoryLine): string {
    const selectedValue = (line.stockItemName || line.description || '').trim();
    if (selectedValue) {
      return selectedValue;
    }
    return this.stockItemSelectOptions[0] || '';
  }

  get currentInventoryLine(): InventoryLine | null {
    return this.inventoryLines[this.activeInventoryLineIndex] || this.inventoryLines[0] || null;
  }

  get inventorySubtotal(): string {
    const total = this.inventoryLines.reduce((sum, line) => sum + (Number(line.amount || '0') || 0), 0);
    return total.toFixed(2);
  }

  get inventoryTaxTotal(): string {
    const total = this.inventoryLines.reduce((sum, line) => {
      const baseAmount = Number(line.amount || '0') || 0;
      const gstRate = Number(line.gstRate || '0') || 0;
      return sum + (baseAmount * gstRate) / 100;
    }, 0);
    return total.toFixed(2);
  }

  get inventoryGrandTotal(): string {
    return (Number(this.inventorySubtotal) + Number(this.inventoryTaxTotal) + Number(this.freightAmount) + Number(this.roundOffAmount)).toFixed(2);
  }

  get inventoryChargesTotal(): string {
    return (Number(this.freightAmount) + Number(this.roundOffAmount)).toFixed(2);
  }

  get inventoryDiscountTotal(): string {
    const total = this.inventoryLines.reduce((sum, line) => {
      const quantity = Number(line.quantity || '0') || 0;
      const rate = Number(line.rate || '0') || 0;
      const gross = quantity * rate;
      const discountRate = Number(line.discount || '0') || 0;
      return sum + (gross * discountRate) / 100;
    }, 0);
    return total.toFixed(2);
  }

  get freightAmount(): string {
    return (Number(this.form.controls.freightAmount.value || '0') || 0).toFixed(2);
  }

  get roundOffAmount(): string {
    return (Number(this.form.controls.roundOffAmount.value || '0') || 0).toFixed(2);
  }

  get taxLedgerLabel(): string {
    const intraState = `${this.form.controls.sellerStateCode.value || ''}` === `${this.form.controls.buyerPlaceOfSupply.value || this.form.controls.buyerStateCode.value || ''}`;
    return this.form.controls.taxLedgerName.value || (intraState ? 'Output CGST/SGST' : 'Output IGST');
  }

  get activeRequest(): {
    path: string;
    action?: string;
    contentType: 'application/json' | 'application/xml';
    payload: unknown;
    company?: string;
  } {
    return this.buildSubmissionRequest(this.selectedEndpoint);
  }

  get selectedStatusLabel(): string {
    if (this.selectedEndpoint.requestKind === 'xml') {
      return 'Direct XML path';
    }
    if (this.selectedEndpoint.requestKind === 'compliance') {
      return 'Compliance flow';
    }
    if (this.selectedEndpoint.createStatus === 'working') {
      return 'Working here';
    }
    if (this.selectedEndpoint.createStatus === 'experimental') {
      return 'Needs setup';
    }
    return 'Currently failing';
  }

  get isVoucherEndpoint(): boolean {
    return this.selectedEndpoint.requestKind === 'voucher';
  }

  get showInventoryVoucherSection(): boolean {
    return this.isVoucherEndpoint && this.usesInventoryEntries((this.form.controls.voucherTypeName.value || '').trim().toLowerCase());
  }

  get isComplianceEndpoint(): boolean {
    return this.selectedEndpoint.requestKind === 'compliance';
  }

  get isXmlEndpoint(): boolean {
    return this.selectedEndpoint.requestKind === 'xml';
  }

  get isGenerateComplianceEndpoint(): boolean {
    return this.isComplianceEndpoint && this.selectedEndpoint.path.includes('/generate');
  }

  get isReadyComplianceEndpoint(): boolean {
    return this.isComplianceEndpoint && this.selectedEndpoint.path.includes('-ready');
  }

  get isEwayComplianceEndpoint(): boolean {
    return this.isComplianceEndpoint && this.selectedEndpoint.path.includes('ewaybill');
  }

  get isEinvoiceComplianceEndpoint(): boolean {
    return this.isComplianceEndpoint && this.selectedEndpoint.path.includes('einvoice');
  }

  get complianceViewTabs(): Array<{ key: ComplianceView; label: string; description: string }> {
    return [
      { key: 'generate', label: 'Generate', description: 'Create voucher and run compliance generation.' },
      { key: 'ready', label: 'Ready', description: 'Mark a sales voucher ready for compliance processing.' },
      { key: 'status', label: 'Status', description: 'Check generated IRN and E-Way values.' },
    ];
  }

  get visibleComplianceEndpoints(): VoucherEndpointOption[] {
    if (this.activeComplianceView === 'generate') {
      return this.endpoints.filter(
        (item) => item.requestKind === 'compliance' && item.path.includes('/generate')
      );
    }
    if (this.activeComplianceView === 'ready') {
      return this.endpoints.filter(
        (item) => item.requestKind === 'compliance' && item.path.includes('-ready')
      );
    }
    return this.endpoints.filter((item) => item.requestKind === 'compliance');
  }

  get showComplianceWorkspace(): boolean {
    return this.isComplianceEndpoint || this.activeComplianceView === 'status';
  }

  get selectedRequestKindLabel(): string {
    if (this.isXmlEndpoint) {
      return 'Raw XML Import';
    }
    if (this.isComplianceEndpoint) {
      return 'Compliance API';
    }
    return 'Voucher Create';
  }

  get workingEndpointCount(): number {
    return this.endpoints.filter((item) => item.createStatus === 'working').length;
  }

  get experimentalEndpointCount(): number {
    return this.endpoints.filter((item) => item.createStatus === 'experimental').length;
  }

  get unsupportedEndpointCount(): number {
    return this.endpoints.filter((item) => item.createStatus === 'unsupported').length;
  }

  get complianceEndpointCount(): number {
    return this.endpoints.filter((item) => item.requestKind === 'compliance').length;
  }

  get submitButtonLabel(): string {
    if (this.isSubmitting) {
      return 'Submitting Request...';
    }
    if (this.isXmlEndpoint) {
      return 'Import XML';
    }
    if (this.isComplianceEndpoint) {
      return 'Run Compliance API';
    }
    return 'Create Voucher';
  }

  get responseStateTone(): 'success' | 'error' | 'neutral' {
    if (this.errorMessage) {
      return 'error';
    }
    if (this.responseText.trim()) {
      return 'success';
    }
    return 'neutral';
  }

  get statusStateTone(): 'success' | 'error' | 'neutral' {
    if (this.statusErrorMessage) {
      return 'error';
    }
    if (this.statusResponseText.trim()) {
      return 'success';
    }
    return 'neutral';
  }

  get documentHighlights(): UiMetric[] {
    return [
      { label: 'Document No', value: this.form.controls.invoiceNumber.value || 'Pending', tone: 'accent' },
      { label: 'Buyer', value: this.form.controls.buyerLegalName.value || this.form.controls.partyLedgerName.value || 'Pending' },
      { label: 'Taxable Value', value: this.form.controls.itemTaxableAmount.value || this.form.controls.amount.value || 'Pending' },
      { label: 'Transport Mode', value: this.showSalesComplianceStudio ? (this.form.controls.transportMode.value || 'Pending') : 'Not required' },
    ];
  }

  get statusLookupHighlights(): UiMetric[] {
    return [
      { label: 'Voucher No', value: this.statusForm.controls.voucherNumber.value || 'Pending', tone: 'accent' },
      { label: 'Party', value: this.statusForm.controls.partyLedgerName.value || 'Pending' },
      { label: 'From Date', value: this.statusForm.controls.fromDate.value || 'Pending' },
      { label: 'To Date', value: this.statusForm.controls.toDate.value || 'Pending' },
    ];
  }

  get requestSummarySections(): UiSection[] {
    if (this.isXmlEndpoint) {
      return [
        {
          title: 'XML Import',
          fields: [
            { label: 'Route', value: this.activeRequest.path },
            { label: 'Mode', value: this.selectedRequestKindLabel },
            { label: 'Action', value: this.activeRequest.action || 'Import' },
            { label: 'Company', value: this.form.controls.company.value || this.currentCompanyName },
          ],
        },
      ];
    }

    if (this.isComplianceEndpoint) {
      return [
        {
          title: 'Compliance Action',
          fields: [
            { label: 'Route', value: this.activeRequest.path },
            { label: 'Action', value: this.activeRequest.action || 'Create' },
            { label: 'Voucher Type', value: this.form.controls.voucherTypeName.value || this.selectedEndpoint.defaultVoucherType },
            { label: 'Invoice Number', value: this.form.controls.invoiceNumber.value || '—' },
          ],
        },
        {
          title: 'Seller',
          fields: [
            { label: 'GSTIN', value: this.form.controls.sellerGstin.value || '—' },
            { label: 'Legal Name', value: this.form.controls.sellerLegalName.value || '—' },
            { label: 'Location', value: this.form.controls.sellerLocation.value || '—' },
            { label: 'State', value: this.form.controls.sellerStateCode.value || '—' },
          ],
        },
        {
          title: 'Buyer',
          fields: [
            { label: 'GSTIN', value: this.form.controls.buyerGstin.value || '—' },
            { label: 'Legal Name', value: this.form.controls.buyerLegalName.value || '—' },
            { label: 'Place of Supply', value: this.form.controls.buyerPlaceOfSupply.value || '—' },
            { label: 'Pincode', value: this.form.controls.buyerPincode.value || '—' },
          ],
        },
        {
          title: 'Invoice Item',
          fields: [
            { label: 'Stock Item', value: this.form.controls.stockItemName.value || '—' },
            { label: 'Description', value: this.form.controls.itemDescription.value || '—' },
            { label: 'HSN / SAC', value: this.form.controls.itemHsnCode.value || '—' },
            { label: 'Quantity', value: this.form.controls.itemQuantity.value || '—' },
            { label: 'GST Rate', value: this.form.controls.itemGstRate.value ? `${this.form.controls.itemGstRate.value}%` : '—' },
          ],
        },
      ];
    }

    return [
      {
        title: 'Voucher Request',
        fields: [
          { label: 'Route', value: this.activeRequest.path },
          { label: 'Action', value: this.activeRequest.action || 'Create' },
          { label: 'Company', value: this.form.controls.company.value || this.currentCompanyName },
          { label: 'Voucher Type', value: this.form.controls.voucherTypeName.value || this.selectedEndpoint.defaultVoucherType },
          { label: 'Voucher Number', value: this.form.controls.voucherNumber.value || '—' },
          { label: 'Stock Item', value: this.form.controls.stockItemName.value || '—' },
          { label: 'Amount', value: this.form.controls.amount.value || '—' },
        ],
      },
    ];
  }

  get responseSections(): UiSection[] {
    return this.buildUiSectionsFromText(this.responseText, 'Voucher Response');
  }

  get statusResponseSections(): UiSection[] {
    return this.buildUiSectionsFromText(this.statusResponseText, 'Compliance Status');
  }

  get responseMetrics(): UiMetric[] {
    return this.buildResponseMetrics(this.responseSections, this.responseText);
  }

  get statusMetrics(): UiMetric[] {
    return this.buildStatusMetrics(this.statusResponseSections, this.statusResponseText);
  }

  get responseTimeline(): UiTimelineStep[] {
    const hasResponse = !!this.responseText.trim();
    const hasStatusData = /Generated|Success|IRN|Ewb|Acknowledg|Writeback/i.test(this.responseText);
    return [
      {
        label: 'Request Prepared',
        status: 'done',
        detail: `${this.selectedEndpoint.label} is configured with business details.`,
      },
      {
        label: 'Submitted To API',
        status: this.isSubmitting ? 'active' : hasResponse ? 'done' : 'pending',
        detail: this.isSubmitting ? 'Request is being sent to backend and connector.' : 'API submission starts when you run the action.',
      },
      {
        label: 'Compliance Processed',
        status: hasStatusData ? 'done' : hasResponse ? 'active' : 'pending',
        detail: hasStatusData
          ? 'Compliance generation and connector writeback details were returned.'
          : 'Waiting for generation or writeback details from the backend.',
      },
      {
        label: 'Result Ready',
        status: hasResponse ? 'done' : 'pending',
        detail: hasResponse ? 'The result summary is available below.' : 'Result summary will appear after a successful run.',
      },
    ];
  }

  get statusTimeline(): UiTimelineStep[] {
    const hasStatus = !!this.statusResponseText.trim();
    const hasGeneratedStatus = /Generated|IRN|Ewb|Active|Success/i.test(this.statusResponseText);
    return [
      {
        label: 'Voucher Search Set',
        status: 'done',
        detail: 'Voucher number, party, and date filters are ready.',
      },
      {
        label: 'Status Requested',
        status: this.isCheckingStatus ? 'active' : hasStatus ? 'done' : 'pending',
        detail: this.isCheckingStatus ? 'Fetching connector compliance status now.' : 'Status API runs when you submit the lookup.',
      },
      {
        label: 'Compliance Match Found',
        status: hasGeneratedStatus ? 'done' : hasStatus ? 'active' : 'pending',
        detail: hasGeneratedStatus
          ? 'Generated compliance values were found in the returned result.'
          : 'Waiting for matching IRN or E-Way values in the response.',
      },
      {
        label: 'Status Ready',
        status: hasStatus ? 'done' : 'pending',
        detail: hasStatus ? 'The status summary is available below.' : 'Status summary will appear after lookup.',
      },
    ];
  }

  applyEndpointPreset(endpointKey: string | null): void {
    const endpoint = this.endpoints.find((item) => item.key === endpointKey) || this.endpoints[0];
    const voucherNumber = endpoint.requestKind === 'compliance' ? 'SI-1001' : (this.lastAutoVoucherNumber || '');
    const preset = this.resolveLedgerPreset(endpoint.defaultVoucherType);
    const action = endpoint.requestKind === 'voucher' ? 'Create' : '';

    this.form.patchValue({
      action,
      company: this.currentCompanyName,
      date: this.currentVoucherDate,
      voucherTypeName: endpoint.defaultVoucherType,
      voucherNumber,
      invoiceNumber: voucherNumber,
      partyLedgerName: preset.partyLedgerName,
      offsetLedgerName: preset.offsetLedgerName,
      narration: 'Created from tally-ui',
      amount: '0.00',
      stockItemName: '',
      freightAmount: '0.00',
      roundOffAmount: '0.00',
      buyerLegalName: preset.partyLedgerName,
      buyerTradeName: preset.partyLedgerName,
      rawXml: this.buildImportXmlEnvelope(endpoint, {
        company: this.currentCompanyName,
        date: this.currentVoucherDate,
        voucherTypeName: endpoint.defaultVoucherType,
        voucherNumber,
        partyLedgerName: preset.partyLedgerName,
        offsetLedgerName: preset.offsetLedgerName,
        narration: 'Created from tally-ui',
        amount: '0.00',
      }),
    });
    this.inventoryLines = [this.createEmptyInventoryLine()];
    this.ensureInventoryLinesHaveSelections();
    this.activeInventoryLineIndex = 0;
    this.syncFormWithInventoryLine(this.inventoryLines[0]);
    this.errorMessage = '';
    this.submitMessage = '';
    this.responseText = '';

    if (endpoint.requestKind === 'compliance') {
      this.activeComplianceView = endpoint.path.includes('-ready') ? 'ready' : 'generate';
    }
    if (endpoint.requestKind === 'voucher') {
      this.refreshAutoVoucherNumber();
    }
  }

  resetForm(): void {
    this.applyEndpointPreset(this.form.controls.endpointKey.value);
  }

  onVoucherEndpointChange(endpointKey: string): void {
    this.form.patchValue({ endpointKey });
    this.applyEndpointPreset(endpointKey);
  }

  onCompanyChange(company: string): void {
    this.companyContextService.setSelectedCompany(company);
  }

  onStockItemChange(stockItemName: string): void {
    this.onInventoryLineFieldChange(this.activeInventoryLineIndex, 'stockItemName', stockItemName);
  }

  onStockItemSelectionChange(index: number, stockItemName: string | null): void {
    const selectedName = stockItemName || '';
    this.setActiveInventoryLine(index);
    this.onInventoryLineFieldChange(index, 'stockItemName', selectedName);
    this.notifyOutOfStockSelection(selectedName);
    this.openStockItemLineIndex = null;
  }

  toggleStockItemDropdown(index: number): void {
    this.setActiveInventoryLine(index);
    const isClosing = this.openStockItemLineIndex === index;
    this.openStockItemLineIndex = isClosing ? null : index;
    this.activeLookupPanel = isClosing ? null : 'stockItemName';
    this.activeLookupIndex = !isClosing && this.filteredStockItemOptions.length ? 0 : -1;
  }

  isStockItemDropdownOpen(index: number): boolean {
    return this.openStockItemLineIndex === index;
  }

  openLookupPanel(panel: 'partyLedgerName' | 'offsetLedgerName' | 'stockItemName'): void {
    if (this.lookupPanelCloseHandle) {
      clearTimeout(this.lookupPanelCloseHandle);
      this.lookupPanelCloseHandle = null;
    }
    this.activeLookupPanel = panel;
    this.activeLookupIndex = this.getCurrentLookupOptionsCount() > 0 ? 0 : -1;
  }

  closeLookupPanelDelayed(): void {
    if (this.lookupPanelCloseHandle) {
      clearTimeout(this.lookupPanelCloseHandle);
    }
    this.lookupPanelCloseHandle = setTimeout(() => {
      this.activeLookupPanel = null;
      this.activeLookupIndex = -1;
      this.lookupPanelCloseHandle = null;
    }, 150);
  }

  selectLedgerOption(field: 'partyLedgerName' | 'offsetLedgerName', value: string): void {
    this.form.patchValue({ [field]: value });
    this.activeLookupPanel = null;
    this.activeLookupIndex = -1;
  }

  selectStockItemOption(index: number, stockItemName: string): void {
    this.onInventoryLineFieldChange(index, 'stockItemName', stockItemName);
    this.activeLookupPanel = null;
    this.activeLookupIndex = -1;
    this.openStockItemLineIndex = null;
  }

  onStockItemLookupFocus(index: number): void {
    this.setActiveInventoryLine(index);
    this.openLookupPanel('stockItemName');
  }

  onStockItemLookupInput(index: number, value: string): void {
    this.setActiveInventoryLine(index);
    this.onInventoryLineFieldChange(index, 'stockItemName', value);
    this.openLookupPanel('stockItemName');
  }

  onLookupInputKeydown(
    event: KeyboardEvent,
    panel: 'partyLedgerName' | 'offsetLedgerName' | 'stockItemName',
    stockLineIndex?: number
  ): void {
    if (this.activeLookupPanel !== panel) {
      return;
    }

    const optionCount = this.getCurrentLookupOptionsCount();
    if (!optionCount) {
      return;
    }

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.activeLookupIndex = Math.min(this.activeLookupIndex + 1, optionCount - 1);
      return;
    }

    if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.activeLookupIndex = Math.max(this.activeLookupIndex - 1, 0);
      return;
    }

    if (event.key === 'Enter') {
      event.preventDefault();
      this.commitHighlightedLookupOption(panel, stockLineIndex);
      return;
    }

    if (event.key === 'Escape') {
      event.preventDefault();
      this.activeLookupPanel = null;
      this.activeLookupIndex = -1;
    }
  }

  onInventoryFieldChange(): void {
    this.syncInventoryAmount();
  }

  setActiveInventoryLine(index: number): void {
    if (index < 0 || index >= this.inventoryLines.length) {
      return;
    }
    this.activeInventoryLineIndex = index;
    this.syncFormWithInventoryLine(this.inventoryLines[index]);
  }

  addInventoryLine(): void {
    this.inventoryLines = [...this.inventoryLines, this.createEmptyInventoryLine()];
    this.ensureInventoryLinesHaveSelections();
    this.setActiveInventoryLine(this.inventoryLines.length - 1);
    this.openStockItemLineIndex = this.inventoryLines.length - 1;
  }

  removeInventoryLine(index: number): void {
    if (this.inventoryLines.length === 1) {
      this.inventoryLines = [this.createEmptyInventoryLine()];
      this.activeInventoryLineIndex = 0;
      this.openStockItemLineIndex = 0;
      this.syncFormWithInventoryLine(this.inventoryLines[0]);
      this.syncInventoryAmount();
      return;
    }
    this.inventoryLines = this.inventoryLines.filter((_, lineIndex) => lineIndex !== index);
    this.activeInventoryLineIndex = Math.min(this.activeInventoryLineIndex, this.inventoryLines.length - 1);
    if (this.openStockItemLineIndex === index) {
      this.openStockItemLineIndex = null;
    } else if (this.openStockItemLineIndex !== null && this.openStockItemLineIndex > index) {
      this.openStockItemLineIndex -= 1;
    }
    this.syncFormWithInventoryLine(this.inventoryLines[this.activeInventoryLineIndex]);
    this.syncInventoryAmount();
  }

  onInventoryLineFieldChange(index: number, field: keyof InventoryLine, value: string): void {
    const nextLines = [...this.inventoryLines];
    const currentLine = { ...nextLines[index] };
    currentLine[field] = value;

    if (field === 'stockItemName') {
      const selectedItem = this.stockItemOptions.find((item) => item.name === value);
      if (selectedItem) {
        currentLine.description = selectedItem.name;
        currentLine.unit = selectedItem.baseUnits || currentLine.unit || 'NOS';
        currentLine.hsnCode = selectedItem.hsnCode || '';
        currentLine.quantity = currentLine.quantity || '1';
        currentLine.rate = selectedItem.rate || currentLine.rate || '0.00';
        currentLine.amount = this.calculateInventoryLineAmount(currentLine.quantity, currentLine.rate, currentLine.discount);
      } else {
        currentLine.description = '';
        currentLine.hsnCode = '';
        currentLine.unit = 'NOS';
        currentLine.rate = '0.00';
        currentLine.amount = '0.00';
      }
    }

    if (field === 'quantity' || field === 'rate' || field === 'discount') {
      currentLine.amount = this.calculateInventoryLineAmount(currentLine.quantity, currentLine.rate, currentLine.discount);
    }

    nextLines[index] = currentLine;
    this.inventoryLines = nextLines;

    if (index === this.activeInventoryLineIndex) {
      this.syncFormWithInventoryLine(currentLine);
    }

    this.syncInventoryAmount();
  }

  onInventoryCellKeydown(
    event: KeyboardEvent,
    index: number,
    field: 'stockItemName' | 'quantity' | 'unit' | 'rate' | 'discount' | 'gstRate'
  ): void {
    if (field === 'stockItemName' && this.activeLookupPanel === 'stockItemName' && this.getCurrentLookupOptionsCount()) {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp' || event.key === 'Enter' || event.key === 'Escape') {
        this.onLookupInputKeydown(event, 'stockItemName', index);
        return;
      }
    }

    if (field === 'stockItemName' && (event.key === 'Enter' || event.key === ' ')) {
      event.preventDefault();
      this.toggleStockItemDropdown(index);
      return;
    }

    if (event.key === 'Enter') {
      event.preventDefault();
      this.moveInventoryFocus(index, field, 'next');
      return;
    }

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.moveInventoryFocus(index, field, 'down');
      return;
    }

    if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.moveInventoryFocus(index, field, 'up');
    }
  }

  setComplianceView(view: ComplianceView): void {
    this.activeComplianceView = view;
    this.errorMessage = '';
    this.submitMessage = '';
    this.statusErrorMessage = '';

    if (view === 'status') {
      return;
    }

    const matchingEndpoint = this.visibleComplianceEndpoints[0];
    if (matchingEndpoint) {
      this.form.patchValue({ endpointKey: matchingEndpoint.key });
      this.applyEndpointPreset(matchingEndpoint.key);
    }
  }

  private loadMasterOptions(): void {
    this.loadLedgerOptions();
    this.loadStockItemOptions();
  }

  private loadLedgerOptions(): void {
    const company = this.resolveMasterLookupCompany();
    const requestId = ++this.ledgerLoadRequestId;
    const cachedLedgers = this.readCachedLedgers(company);
    if (cachedLedgers.length) {
      this.ledgerOptions = cachedLedgers;
      this.syncLedgerSelectionsWithOptions();
    }
    this.isLoadingLedgers = true;
    this.mastersApiService.getLedgers(company).subscribe({
      next: (response) => {
        if (requestId !== this.ledgerLoadRequestId) {
          return;
        }
        const rawLedgers = this.toArray(response?.data || response?.LEDGER);
        const nextOptions = rawLedgers
          .map((item) => this.readText(item, 'NAME', 'name'))
          .filter((name) => !!name)
          .sort((left, right) => left.localeCompare(right));
        if (!nextOptions.length && this.shouldRetryForFallbackCompany(company)) {
          this.retryLedgerOptionsForFallbackCompany(company);
          return;
        }
        if (!nextOptions.length && cachedLedgers.length) {
          this.ledgerOptions = cachedLedgers;
          this.syncLedgerSelectionsWithOptions();
          this.isLoadingLedgers = false;
          return;
        }
        this.ledgerOptions = nextOptions;
        this.writeCachedLedgers(company, nextOptions);
        this.syncLedgerSelectionsWithOptions();
        this.isLoadingLedgers = false;
      },
      error: () => {
        if (requestId !== this.ledgerLoadRequestId) {
          return;
        }
        this.ledgerOptions = cachedLedgers;
        this.syncLedgerSelectionsWithOptions();
        this.isLoadingLedgers = false;
      },
    });
  }

  private loadStockItemOptions(): void {
    const company = this.resolveMasterLookupCompany();
    const requestId = ++this.stockItemLoadRequestId;
    const cachedStockItems = this.readCachedStockItems(company);
    if (cachedStockItems.length) {
      this.stockItemOptions = cachedStockItems;
      this.stockItemNameOptions = cachedStockItems.map((item) => item.name);
      this.syncInventoryLinesWithStockOptions();
      this.ensureInventorySelection();
      this.applySelectedStockItemDefaults();
    }
    this.isLoadingStockItems = true;
    this.mastersApiService.getStockItems(company).subscribe({
      next: (response) => {
        if (requestId !== this.stockItemLoadRequestId) {
          return;
        }
        const nextOptions = this.extractStockItemOptions(response);
        if (!nextOptions.length && this.shouldRetryForFallbackCompany(company)) {
          this.retryStockItemOptionsForFallbackCompany(company);
          return;
        }
        if (!nextOptions.length && cachedStockItems.length) {
          this.stockItemOptions = cachedStockItems;
          this.stockItemNameOptions = cachedStockItems.map((item) => item.name);
          this.syncInventoryLinesWithStockOptions();
          this.ensureInventorySelection();
          this.applySelectedStockItemDefaults();
          this.isLoadingStockItems = false;
          return;
        }
        this.stockItemOptions = nextOptions;
        this.stockItemNameOptions = nextOptions.map((item) => item.name);
        this.writeCachedStockItems(company, nextOptions);
        this.syncInventoryLinesWithStockOptions();
        this.ensureInventoryLinesHaveSelections();
        this.ensureInventorySelection();
        this.applySelectedStockItemDefaults();
        this.isLoadingStockItems = false;
      },
      error: () => {
        if (requestId !== this.stockItemLoadRequestId) {
          return;
        }
        this.stockItemOptions = cachedStockItems;
        this.stockItemNameOptions = cachedStockItems.map((item) => item.name);
        this.syncInventoryLinesWithStockOptions();
        this.ensureInventorySelection();
        this.applySelectedStockItemDefaults();
        this.isLoadingStockItems = false;
      },
    });
  }

  private formatVoucherDate(value: Date): string {
    const year = value.getFullYear();
    const month = `${value.getMonth() + 1}`.padStart(2, '0');
    const day = `${value.getDate()}`.padStart(2, '0');
    return `${year}${month}${day}`;
  }

  private toDateInputValue(value: string): string {
    if (/^\d{8}$/.test(value)) {
      return `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)}`;
    }
    return value;
  }

  private toVoucherDateValue(value: string | null | undefined): string {
    const normalized = `${value || ''}`.trim();
    if (/^\d{4}-\d{2}-\d{2}$/.test(normalized)) {
      return normalized.replace(/-/g, '');
    }
    return normalized;
  }

  private loadLatestAcceptedVoucherDate(company: string): void {
    const normalizedCompany = (company || '').trim();
    if (!normalizedCompany) {
      return;
    }
    const previousDefaultDate = this.currentVoucherDate;
    this.reportsApiService.getDayBook('20200101', previousDefaultDate, 'raw', normalizedCompany).subscribe({
      next: (response) => {
        const latestDate = this.extractLatestVoucherDate(response);
        if (!latestDate || latestDate === this.currentVoucherDate) {
          this.loadLatestAcceptedSalesVoucherDate(normalizedCompany, previousDefaultDate);
          return;
        }
        this.currentVoucherDate = latestDate;
        this.latestAcceptedVoucherDate = latestDate;
        this.patchAutoDefaultDates(previousDefaultDate, latestDate);
      },
      error: () => {
        this.loadLatestAcceptedSalesVoucherDate(normalizedCompany, previousDefaultDate);
      },
    });
  }

  private loadLatestAcceptedSalesVoucherDate(company: string, previousDefaultDate: string): void {
    this.vouchersApiService.getSalesVouchers(company, '20200101', previousDefaultDate, 100).subscribe({
      next: (response) => {
        const latestDate = this.extractLatestVoucherDate(response);
        if (!latestDate || latestDate === this.currentVoucherDate) {
          return;
        }
        this.currentVoucherDate = latestDate;
        this.latestAcceptedVoucherDate = latestDate;
        this.patchAutoDefaultDates(previousDefaultDate, latestDate);
      },
      error: () => {
        // Keep the machine date fallback when Tally/report data is unavailable.
      },
    });
  }

  private refreshAutoVoucherNumber(onResolved?: () => void): void {
    const endpoint = this.selectedEndpoint;
    if (endpoint.requestKind !== 'voucher') {
      if (onResolved) {
        onResolved();
      }
      return;
    }
    this.generateAndApplyNextVoucherNumber(onResolved, false);
  }

  private generateAndApplyNextVoucherNumber(onResolved?: () => void, force = true): void {
    const endpoint = this.selectedEndpoint;
    if (endpoint.requestKind !== 'voucher') {
      if (onResolved) {
        onResolved();
      }
      return;
    }

    const company = (this.form.controls.company.value || this.currentCompanyName || '').trim();
    const voucherType = (this.form.controls.voucherTypeName.value || endpoint.defaultVoucherType || '').trim();
    if (!company || !voucherType) {
      if (onResolved) {
        onResolved();
      }
      return;
    }

    const currentVoucherNumber = (this.form.controls.voucherNumber.value || '').trim();
    if (!force && currentVoucherNumber && currentVoucherNumber !== this.lastAutoVoucherNumber) {
      if (onResolved) {
        onResolved();
      }
      return;
    }

    const requestId = ++this.voucherNumberLoadRequestId;
    const connectorPath = endpoint.path.replace(/^\/api/, '');
    const toDate = this.latestAcceptedVoucherDate || this.currentVoucherDate || this.formatVoucherDate(new Date());

    const numberingRules$ = this.settingsApiService
      .getNumberingRules(company)
      .pipe(catchError(() => of(null)));
    const dayBook$ = this.reportsApiService
      .getDayBook('20200101', toDate, 'raw', company)
      .pipe(catchError(() => of(null)));
    const queue$ = this.vouchersApiService
      .getVoucherQueue({ company, connectorPath, limit: 1000 })
      .pipe(catchError(() => of({ entries: [] })));

    forkJoin([numberingRules$, dayBook$, queue$]).subscribe({
      next: ([numberingRulesResponse, dayBookResponse, queueResponse]) => {
        if (requestId !== this.voucherNumberLoadRequestId) {
          return;
        }

        const existingNumbers = [
          ...this.extractVoucherNumbersFromDayBook(dayBookResponse, voucherType),
          ...this.extractVoucherNumbersFromQueue(queueResponse, voucherType),
        ];
        const nextVoucherNumber = this.computeNextVoucherNumber(existingNumbers, voucherType, numberingRulesResponse);
        const invoiceControl = this.form.controls.invoiceNumber;
        const currentInvoiceNumber = (invoiceControl.value || '').trim();
        const shouldPatchInvoice = !currentInvoiceNumber || currentInvoiceNumber === this.lastAutoVoucherNumber || currentInvoiceNumber === currentVoucherNumber;
        const nextPatch: Record<string, string> = {
          voucherNumber: nextVoucherNumber,
        };
        if (shouldPatchInvoice) {
          nextPatch['invoiceNumber'] = nextVoucherNumber;
        }
        this.lastAutoVoucherNumber = nextVoucherNumber;
        this.form.patchValue(nextPatch);
        if (onResolved) {
          onResolved();
        }
      },
      error: () => {
        if (requestId !== this.voucherNumberLoadRequestId) {
          return;
        }
        if (onResolved) {
          onResolved();
        }
      },
    });
  }

  private extractVoucherNumbersFromDayBook(response: any, voucherType: string): string[] {
    const normalizedVoucherType = voucherType.trim().toLowerCase();
    return this.toArray(response?.data || response)
      .filter((item) => this.readText(item, 'voucherType', 'VOUCHERTYPENAME').trim().toLowerCase() === normalizedVoucherType)
      .map((item) => this.readText(item, 'voucherNumber', 'VOUCHERNUMBER', 'REFERENCE'))
      .filter((value) => !!value && !value.toUpperCase().startsWith('OFF-'));
  }

  private extractVoucherNumbersFromQueue(response: any, voucherType: string): string[] {
    const normalizedVoucherType = voucherType.trim().toLowerCase();
    return this.toArray(response?.entries || response)
      .map((entry) => {
        const originalVoucherNumber = this.readText(entry, 'originalVoucherNumber', 'original_voucher_number');
        const payloadText = this.readText(entry, 'requestBody', 'request_body');
        const payload = payloadText ? this.parseJsonRecord(payloadText) : null;
        const voucher = payload ? this.unwrapVoucherPayloadForGeneration(payload as Record<string, unknown>) : null;
        const entryVoucherType = this.readText(voucher?.['VOUCHERTYPENAME'] || entry?.['voucherType']);
        if (entryVoucherType.trim().toLowerCase() !== normalizedVoucherType) {
          return '';
        }
        return originalVoucherNumber
          || this.readText(voucher?.['VOUCHERNUMBER'])
          || this.readText(voucher?.['REFERENCE']);
      })
      .filter((value): value is string => typeof value === 'string' && !!value && !value.toUpperCase().startsWith('OFF-'));
  }

  private computeNextVoucherNumber(existingNumbers: string[], voucherType: string, numberingRulesResponse?: any): string {
    const uniqueNumbers = Array.from(new Set(existingNumbers.map((value) => `${value || ''}`.trim()).filter(Boolean)));
    const tallyRule = this.extractTallyNumberingRule(numberingRulesResponse, voucherType);
    let bestPrefix = tallyRule?.prefix ?? this.defaultVoucherNumberPrefix(voucherType);
    let bestWidth = tallyRule?.width ?? 3;
    let maxNumber = tallyRule?.baseNumber ?? 0;

    uniqueNumbers.forEach((value) => {
      const parsed = this.parseVoucherNumberPattern(value);
      if (!parsed) {
        return;
      }
      if (parsed.number > maxNumber) {
        maxNumber = parsed.number;
        bestPrefix = parsed.prefix;
        bestWidth = parsed.width;
      }
    });

    let nextVoucherNumber = maxNumber > 0
      ? this.formatVoucherNumber(bestPrefix, maxNumber + 1, bestWidth)
      : this.formatVoucherNumber(bestPrefix, 1, bestWidth);

    while (uniqueNumbers.includes(nextVoucherNumber)) {
      const parsed = this.parseVoucherNumberPattern(nextVoucherNumber);
      if (!parsed) {
        break;
      }
      nextVoucherNumber = this.formatVoucherNumber(parsed.prefix, parsed.number + 1, parsed.width);
    }
    return nextVoucherNumber;
  }

  private unwrapVoucherPayloadForGeneration(payload: Record<string, unknown> | null): Record<string, unknown> | null {
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

  private parseVoucherNumberPattern(value: string): { prefix: string; number: number; width: number } | null {
    const normalized = `${value || ''}`.trim();
    if (!normalized || normalized.toUpperCase().startsWith('OFF-')) {
      return null;
    }
    const match = normalized.match(/^(.*?)(\d+)$/);
    if (!match) {
      return null;
    }
    return {
      prefix: match[1] || '',
      number: Number(match[2]),
      width: match[2].length,
    };
  }

  private formatVoucherNumber(prefix: string, number: number, width: number): string {
    return `${prefix}${`${Math.max(1, number)}`.padStart(width, '0')}`;
  }

  private defaultVoucherNumberPrefix(voucherType: string): string {
    const normalized = voucherType.trim().toLowerCase();
    if (normalized === 'sales') {
      return '';
    }
    if (normalized === 'purchase') {
      return 'PUR-';
    }
    if (normalized === 'payment') {
      return 'PAY-';
    }
    if (normalized === 'receipt') {
      return 'REC-';
    }
    if (normalized === 'contra') {
      return 'CON-';
    }
    if (normalized === 'journal') {
      return 'JRN-';
    }
    if (normalized === 'credit note') {
      return 'CN-';
    }
    if (normalized === 'debit note') {
      return 'DN-';
    }
    const compact = normalized.replace(/[^a-z0-9]+/g, ' ').trim();
    if (!compact) {
      return 'VCH-';
    }
    return `${compact.split(' ').map((part) => part.slice(0, 1).toUpperCase()).join('')}-`;
  }

  private extractTallyNumberingRule(response: any, voucherType: string): { prefix: string; width: number; baseNumber: number } | null {
    const normalizedVoucherType = voucherType.trim().toLowerCase();
    const rows = this.toArray(response?.data || response);
    for (const row of rows) {
      if (!row || typeof row !== 'object') {
        continue;
      }
      const record = row as Record<string, unknown>;
      const detailError = this.readText(record['detail'], 'error_type');
      if (detailError) {
        continue;
      }
      const candidateVoucherType = this.readText(
        record,
        'voucherType',
        'voucher_type',
        'VOUCHERTYPENAME',
        'name',
        'NAME'
      );
      if (candidateVoucherType && candidateVoucherType.trim().toLowerCase() !== normalizedVoucherType) {
        continue;
      }
      const explicitNext = this.readText(record, 'nextNumber', 'next_number', 'nextVoucherNumber', 'next_voucher_number');
      const explicitStart = this.readText(record, 'startingNumber', 'starting_number', 'startNumber', 'start_number');
      const prefix = this.readText(record, 'prefix', 'PREFIX', 'numberingPrefix', 'numbering_prefix');
      const widthValue = this.readText(record, 'width', 'WIDTH', 'digitWidth', 'digit_width');
      const numericSeed = explicitNext || explicitStart;
      const parsedFromSeed = this.parseVoucherNumberPattern(numericSeed);
      if (parsedFromSeed) {
        return {
          prefix: prefix || parsedFromSeed.prefix,
          width: Number(widthValue || parsedFromSeed.width) || parsedFromSeed.width,
          baseNumber: Math.max(0, parsedFromSeed.number - (explicitNext ? 1 : 0)),
        };
      }
      if (prefix || widthValue) {
        return {
          prefix: prefix || this.defaultVoucherNumberPrefix(voucherType),
          width: Number(widthValue) || 3,
          baseNumber: 0,
        };
      }
    }
    return null;
  }

  private patchAutoDefaultDates(previousDate: string, nextDate: string): void {
    const previousInputDate = this.toDateInputValue(previousDate);
    const formDate = this.form.controls.date.value || '';
    if (!formDate || formDate === previousInputDate) {
      this.form.patchValue({ date: this.toDateInputValue(nextDate) });
    }
    const fromDate = this.statusForm.controls.fromDate.value || '';
    const toDate = this.statusForm.controls.toDate.value || '';
    this.statusForm.patchValue({
      fromDate: !fromDate || fromDate === previousInputDate ? this.toDateInputValue(nextDate) : fromDate,
      toDate: !toDate || toDate === previousInputDate ? this.toDateInputValue(nextDate) : toDate,
    });
  }

  private extractLatestVoucherDate(response: any): string {
    const vouchers = this.toArray(response?.data || response?.VOUCHER || response);
    return vouchers
      .map((item) => this.readText(item, 'DATE', 'date'))
      .filter((date) => /^\d{8}$/.test(date))
      .sort()
      .pop() || '';
  }

  private resolveMasterLookupCompany(): string {
    const candidates = [
      this.currentCompanyName,
      this.form.controls.company.value,
      this.companyOptions[0] || '',
    ];
    for (const candidate of candidates) {
      const normalized = (candidate || '').trim();
      if (normalized) {
        return normalized;
      }
    }
    return '';
  }

  private shouldRetryForFallbackCompany(company: string): boolean {
    const fallbackCompany = (this.companyOptions[0] || '').trim();
    return !!fallbackCompany && fallbackCompany !== (company || '').trim();
  }

  private retryLedgerOptionsForFallbackCompany(previousCompany: string): void {
    const fallbackCompany = (this.companyOptions[0] || '').trim();
    const requestId = this.ledgerLoadRequestId;
    if (!fallbackCompany || fallbackCompany === (previousCompany || '').trim()) {
      this.ledgerOptions = this.readCachedLedgers(previousCompany);
      this.syncLedgerSelectionsWithOptions();
      this.isLoadingLedgers = false;
      return;
    }
    this.mastersApiService.getLedgers(fallbackCompany).subscribe({
      next: (response) => {
        if (requestId !== this.ledgerLoadRequestId) {
          return;
        }
        const rawLedgers = this.toArray(response?.data || response?.LEDGER);
        this.ledgerOptions = rawLedgers
          .map((item) => this.readText(item, 'NAME', 'name'))
          .filter((name) => !!name)
          .sort((left, right) => left.localeCompare(right));
        this.writeCachedLedgers(fallbackCompany, this.ledgerOptions);
        this.syncLedgerSelectionsWithOptions();
        this.isLoadingLedgers = false;
      },
      error: () => {
        if (requestId !== this.ledgerLoadRequestId) {
          return;
        }
        this.ledgerOptions = this.readCachedLedgers(previousCompany);
        this.syncLedgerSelectionsWithOptions();
        this.isLoadingLedgers = false;
      },
    });
  }

  private retryStockItemOptionsForFallbackCompany(previousCompany: string): void {
    const fallbackCompany = (this.companyOptions[0] || '').trim();
    const requestId = this.stockItemLoadRequestId;
    if (!fallbackCompany || fallbackCompany === (previousCompany || '').trim()) {
      this.stockItemOptions = this.readCachedStockItems(previousCompany);
      this.stockItemNameOptions = this.stockItemOptions.map((item) => item.name);
      this.syncInventoryLinesWithStockOptions();
      this.ensureInventorySelection();
      this.applySelectedStockItemDefaults();
      this.isLoadingStockItems = false;
      return;
    }
    this.mastersApiService.getStockItems(fallbackCompany).subscribe({
      next: (response) => {
        if (requestId !== this.stockItemLoadRequestId) {
          return;
        }
        this.stockItemOptions = this.extractStockItemOptions(response);
        this.stockItemNameOptions = this.stockItemOptions.map((item) => item.name);
        this.writeCachedStockItems(fallbackCompany, this.stockItemOptions);
        this.syncInventoryLinesWithStockOptions();
        this.ensureInventoryLinesHaveSelections();
        this.ensureInventorySelection();
        this.applySelectedStockItemDefaults();
        this.isLoadingStockItems = false;
      },
      error: () => {
        if (requestId !== this.stockItemLoadRequestId) {
          return;
        }
        this.stockItemOptions = this.readCachedStockItems(previousCompany);
        this.stockItemNameOptions = this.stockItemOptions.map((item) => item.name);
        this.syncInventoryLinesWithStockOptions();
        this.ensureInventorySelection();
        this.applySelectedStockItemDefaults();
        this.isLoadingStockItems = false;
      },
    });
  }

  submit(): void {
    if (this.isVoucherEndpoint) {
      this.generateAndApplyNextVoucherNumber(() => this.submitVoucherRequest());
      return;
    }
    this.submitVoucherRequest();
  }

  private submitVoucherRequest(): void {
    if (this.form.invalid || this.isSubmitting) {
      this.form.markAllAsTouched();
      return;
    }

    if (this.showInventoryVoucherSection) {
      const validationMessage = this.validateInventoryVoucher();
      if (validationMessage) {
        this.errorMessage = validationMessage;
        return;
      }
    }

    const endpoint = this.selectedEndpoint;
    const submissionDate = this.resolveSubmissionVoucherDate(this.form.controls.date.value);
    if (submissionDate !== this.toVoucherDateValue(this.form.controls.date.value)) {
      this.form.patchValue({ date: this.toDateInputValue(submissionDate) });
    }
    this.isSubmitting = true;
    this.errorMessage = '';
    this.submitMessage = '';
    this.responseText = '';

    const request = this.activeRequest;

    this.vouchersApiService
      .createVoucher(request)
      .subscribe({
        next: (response) => {
          this.isSubmitting = false;
          const responseText = typeof response === 'string' ? response : JSON.stringify(response, null, 2);
          const parsedResponse = this.parseJsonRecord(responseText);
          this.submitMessage = this.resolveSubmitMessage(endpoint.label, parsedResponse);
          this.responseText = responseText;
        },
        error: (error: HttpErrorResponse) => {
          const parsedError = this.parseJsonErrorBody(error.error);
          if (this.shouldStoreVoucherOffline(error, parsedError)) {
            this.storeVoucherOffline(request, endpoint.label, error, parsedError);
            return;
          }
          this.isSubmitting = false;
          this.errorMessage = this.resolveError(error);
          this.responseText = this.resolveResponse(error);
          this.handleDuplicateVoucherError(error);
        },
      });
  }

  submitStatusLookup(): void {
    if (this.statusForm.invalid || this.isCheckingStatus) {
      this.statusForm.markAllAsTouched();
      return;
    }

    this.isCheckingStatus = true;
    this.statusErrorMessage = '';
    this.statusResponseText = '';

    const rawValue = this.statusForm.getRawValue();
    const request: VoucherComplianceStatusRequest = {
      path: this.selectedStatusEndpoint.path,
      company: rawValue.company || this.currentCompanyName,
      voucherNumber: rawValue.voucherNumber || '',
      reference: rawValue.reference || '',
      partyLedgerName: rawValue.partyLedgerName || '',
      fromDate: this.toVoucherDateValue(rawValue.fromDate) || '',
      toDate: this.toVoucherDateValue(rawValue.toDate) || '',
      limit: rawValue.limit || '20',
    };

    this.vouchersApiService.getComplianceStatus(request).subscribe({
      next: (response) => {
        this.isCheckingStatus = false;
        this.statusResponseText = typeof response === 'string' ? response : JSON.stringify(response, null, 2);
      },
      error: (error: HttpErrorResponse) => {
        this.isCheckingStatus = false;
        this.statusErrorMessage = this.resolveError(error);
        this.statusResponseText = this.resolveResponse(error);
      },
    });
  }

  runSalesCompliance(mode: 'einvoice' | 'ewaybill' | 'both'): void {
    if (!this.canRunSalesCompliance || this.isRunningCompliance) {
      return;
    }

    const endpointKey =
      mode === 'einvoice'
        ? 'sales-einvoice-generate'
        : mode === 'ewaybill'
          ? 'sales-ewaybill-generate'
          : 'sales-einvoice-ewaybill-generate';
    const endpoint = this.endpoints.find((item) => item.key === endpointKey);
    if (!endpoint) {
      this.complianceErrorMessage = 'Requested compliance action is not configured.';
      return;
    }

    this.isRunningCompliance = true;
    this.complianceMessage = '';
    this.complianceErrorMessage = '';
    this.complianceResponseText = '';

    this.vouchersApiService.createVoucher({
      path: endpoint.path,
      contentType: endpoint.contentType,
      company: this.form.controls.company.value || this.currentCompanyName,
      payload: this.buildCompliancePayload(endpoint),
    }).subscribe({
      next: (response) => {
        this.isRunningCompliance = false;
        this.complianceResponseText = typeof response === 'string' ? response : JSON.stringify(response, null, 2);
        this.complianceMessage = `${endpoint.label} completed successfully.`;
      },
      error: (error: HttpErrorResponse) => {
        this.isRunningCompliance = false;
        this.complianceErrorMessage = this.resolveError(error);
        this.complianceResponseText = this.resolveResponse(error);
      },
    });
  }

  private buildSubmissionRequest(endpoint: VoucherEndpointOption): {
    path: string;
    action?: string;
    contentType: 'application/json' | 'application/xml';
    payload: unknown;
    company?: string;
  } {
    const rawAction = (this.form.controls.action.value || '').trim();

    if (endpoint.requestKind === 'xml') {
      return {
        path: endpoint.path,
        contentType: 'application/xml',
        company: this.form.controls.company.value || this.currentCompanyName,
        payload: (this.form.controls.rawXml.value || '').trim() || this.buildImportXmlEnvelope(endpoint, this.form.getRawValue()),
      };
    }

    if (endpoint.requestKind === 'compliance') {
      return {
        path: endpoint.path,
        action: rawAction || undefined,
        contentType: 'application/json',
        company: this.form.controls.company.value || this.currentCompanyName,
        payload: this.buildCompliancePayload(endpoint),
      };
    }

    return {
      path: endpoint.path,
      action: rawAction || 'Create',
      contentType: endpoint.contentType,
      company: this.form.controls.company.value || this.currentCompanyName,
      payload: this.buildVoucherRequestPayload(endpoint),
    };
  }

  private resolveSubmissionVoucherDate(value: string | null | undefined): string {
    const requestedDate = this.toVoucherDateValue(value) || this.currentVoucherDate;
    const latestAcceptedDate = this.latestAcceptedVoucherDate || this.currentVoucherDate;
    if (!/^\d{8}$/.test(requestedDate) || !/^\d{8}$/.test(latestAcceptedDate)) {
      return requestedDate;
    }
    return requestedDate > latestAcceptedDate ? latestAcceptedDate : requestedDate;
  }

  private buildCompliancePayload(endpoint: VoucherEndpointOption): Record<string, unknown> {
    const rawValue = this.form.getRawValue();
    const voucherDate = this.resolveSubmissionVoucherDate(rawValue.date);
    const activeLines = this.getEffectiveInventoryLines(rawValue);
    const taxableAmount = activeLines.reduce((sum, line) => sum + (Number(line.amount || '0') || 0), 0) || Number(rawValue.itemTaxableAmount || rawValue.amount || '1000.00');
    const isIntraState = `${rawValue.sellerStateCode || ''}` === `${rawValue.buyerPlaceOfSupply || rawValue.buyerStateCode || ''}`;
    const totalDiscount = Number(this.inventoryDiscountTotal);
    const totalTax = activeLines.reduce((sum, line) => {
      const lineAmount = Number(line.amount || '0') || 0;
      const gstRate = Number(line.gstRate || rawValue.itemGstRate || '18') || 0;
      return sum + (lineAmount * gstRate) / 100;
    }, 0);
    const cgstAmount = isIntraState ? Number((totalTax / 2).toFixed(2)) : 0;
    const sgstAmount = isIntraState ? Number((totalTax / 2).toFixed(2)) : 0;
    const igstAmount = isIntraState ? 0 : Number(totalTax.toFixed(2));
    const totalOtherCharge = Number(rawValue.itemOtherCharge || '0') + Number(rawValue.freightAmount || '0') + Number(rawValue.roundOffAmount || '0');
    const totalInvoiceValue = Number((taxableAmount + cgstAmount + sgstAmount + igstAmount + totalOtherCharge).toFixed(2));

    return {
      voucher_type: rawValue.voucherTypeName || endpoint.defaultVoucherType,
      voucher: {
        COMPANY: rawValue.company || this.currentCompanyName,
        DATE: voucherDate,
        VOUCHERTYPENAME: rawValue.voucherTypeName || endpoint.defaultVoucherType,
        VOUCHERNUMBER: rawValue.invoiceNumber || rawValue.voucherNumber || '',
        REFERENCE: rawValue.invoiceNumber || rawValue.voucherNumber || '',
        PARTYLEDGERNAME: rawValue.partyLedgerName || rawValue.buyerLegalName || '',
        PARTYGSTIN: rawValue.buyerGstin || '',
        PLACEOFSUPPLY: rawValue.buyerPlaceOfSupply || rawValue.buyerStateCode || '',
        NARRATION: rawValue.narration || '',
      },
      seller: {
        gstin: rawValue.sellerGstin || '',
        legal_name: rawValue.sellerLegalName || '',
        trade_name: rawValue.sellerLegalName || '',
        address1: rawValue.sellerAddress1 || '',
        address2: rawValue.sellerAddress2 || '',
        location: rawValue.sellerLocation || '',
        pincode: rawValue.sellerPincode || '',
        state_code: rawValue.sellerStateCode || '',
        email: rawValue.sellerEmail || '',
        phone_number: rawValue.sellerPhone || '',
      },
      buyer: {
        gstin: rawValue.buyerGstin || '',
        legal_name: rawValue.buyerLegalName || '',
        trade_name: rawValue.buyerTradeName || rawValue.buyerLegalName || '',
        address1: rawValue.buyerAddress1 || '',
        address2: rawValue.buyerAddress2 || '',
        location: rawValue.buyerLocation || '',
        pincode: rawValue.buyerPincode || '',
        state_code: rawValue.buyerStateCode || '',
        place_of_supply: rawValue.buyerPlaceOfSupply || rawValue.buyerStateCode || '',
        email: rawValue.buyerEmail || '',
        phone_number: rawValue.buyerPhone || '',
      },
      dispatch_from: {
        address1: rawValue.dispatchAddress1 || '',
        address2: rawValue.dispatchAddress2 || '',
        location: rawValue.dispatchLocation || '',
        pincode: rawValue.dispatchPincode || '',
        state_code: rawValue.dispatchStateCode || '',
        company_name: rawValue.sellerLegalName || '',
      },
      ship_to: {
        gstin: rawValue.buyerGstin || '',
        legal_name: rawValue.buyerLegalName || '',
        address1: rawValue.shipAddress1 || rawValue.buyerAddress1 || '',
        address2: rawValue.shipAddress2 || rawValue.buyerAddress2 || '',
        location: rawValue.shipLocation || rawValue.buyerLocation || '',
        pincode: rawValue.shipPincode || rawValue.buyerPincode || '',
        state_code: rawValue.shipStateCode || rawValue.buyerStateCode || '',
      },
      items: activeLines.map((line) => {
        const lineAmount = Number(line.amount || '0') || 0;
        const quantity = Number(line.quantity || '1') || 1;
        const gstRate = Number(line.gstRate || rawValue.itemGstRate || '18') || 0;
        const lineCgst = isIntraState ? Number(((lineAmount * gstRate) / 200).toFixed(2)) : 0;
        const lineSgst = isIntraState ? Number(((lineAmount * gstRate) / 200).toFixed(2)) : 0;
        const lineIgst = isIntraState ? 0 : Number(((lineAmount * gstRate) / 100).toFixed(2));
        const grossValue = quantity * (Number(line.rate || '0') || 0);
        const discountRate = Number(line.discount || '0') || 0;
        const discountValue = Number(((grossValue * discountRate) / 100).toFixed(2));
        return {
          stock_item_name: line.stockItemName || line.description || '',
          description: line.description || line.stockItemName || '',
          hsn_code: line.hsnCode || '',
          quantity,
          billed_quantity: quantity,
          unit: line.unit || 'NOS',
          rate: line.rate || rawValue.amount || '1000.00',
          amount: lineAmount,
          taxable_amount: lineAmount,
          gst_rate: gstRate,
          cgst_amount: lineCgst,
          sgst_amount: lineSgst,
          igst_amount: lineIgst,
          discount: discountValue,
          other_charge: Number(rawValue.itemOtherCharge || '0') + Number(rawValue.freightAmount || '0'),
        };
      }),
      value_details: {
        total_assessable_value: taxableAmount,
        total_cgst_value: cgstAmount,
        total_sgst_value: sgstAmount,
        total_igst_value: igstAmount,
        total_invoice_value: totalInvoiceValue,
        total_discount: totalDiscount,
        total_other_charge: totalOtherCharge,
      },
      ewaybill: {
        transporter_id: rawValue.transporterId || '',
        transporter_name: rawValue.transporterName || '',
        transport_mode: rawValue.transportMode || '',
        distance_km: rawValue.distanceKm || '',
        vehicle_number: rawValue.vehicleNumber || '',
        vehicle_type: rawValue.vehicleType || '',
        transport_document_no: rawValue.transportDocumentNo || '',
        transport_document_date: rawValue.transportDocumentDate || '',
      },
    };
  }

  private buildVoucherRequestPayload(endpoint: VoucherEndpointOption): {
    VOUCHER: Array<Record<string, unknown>>;
  } {
    const rawValue = this.form.getRawValue();
    const company = rawValue.company || this.currentCompanyName;
    const voucherType = rawValue.voucherTypeName || endpoint.defaultVoucherType;
    const date = this.resolveSubmissionVoucherDate(rawValue.date);
    const voucherNumber = rawValue.voucherNumber || '';
    const narration = rawValue.narration || '';
    const partyLedgerName = rawValue.partyLedgerName || '';
    const amount = Number(this.showInventoryVoucherSection ? this.inventoryGrandTotal : (rawValue.amount || '1000.00'));
    const normalizedType = voucherType.trim().toLowerCase();
    const shouldUseInventoryEntries = this.usesInventoryEntries(normalizedType);
    const effectiveOffsetLedgerName = this.resolveEffectiveOffsetLedgerName(normalizedType, rawValue.offsetLedgerName || '');

    const voucher: Record<string, unknown> = {
      COMPANY: company,
      DATE: date,
      EFFECTIVEDATE: date,
      VOUCHERTYPENAME: voucherType,
      VOUCHERNUMBER: voucherNumber,
      NARRATION: narration,
    };

    const shouldIncludePartyLedgerName = this.isPartyVoucherType(normalizedType);

    if (partyLedgerName && shouldIncludePartyLedgerName) {
      voucher['PARTYLEDGERNAME'] = partyLedgerName;
    }

    if (shouldUseInventoryEntries) {
      voucher['PERSISTEDVIEW'] = 'Invoice Voucher View';
      voucher['ISINVOICE'] = 'Yes';
      voucher['REFERENCE'] = rawValue.invoiceNumber || voucherNumber;
      voucher['ACCOUNTINGLEDGERNAME'] = effectiveOffsetLedgerName;
      voucher['ALLINVENTORYENTRIES.LIST'] = this.buildInventoryEntries(
        voucherType,
        rawValue,
        amount,
        effectiveOffsetLedgerName
      );
    }

    if (this.usesLedgerEntries(normalizedType)) {
      voucher['ALLLEDGERENTRIES.LIST'] = this.buildLedgerEntries(
        voucherType,
        partyLedgerName,
        effectiveOffsetLedgerName,
        amount,
        rawValue
      );
    } else if (partyLedgerName) {
      voucher['AMOUNT'] = amount.toFixed(2);
    }

    return {
      VOUCHER: [
        voucher,
      ],
    };
  }

  private buildInventoryEntries(
    voucherType: string,
    rawValue: Record<string, any>,
    voucherAmount: number,
    accountingLedgerName: string
  ): Array<Record<string, unknown>> {
    const normalizedType = voucherType.trim().toLowerCase();
    const lines = this.getEffectiveInventoryLines(rawValue);

    return lines.map((line) => {
      const stockItemName = line.stockItemName || line.description || '';
      const quantityValue = Number(line.quantity || '1') || 1;
      const unit = line.unit || this.resolveStockItemBaseUnits(stockItemName) || 'NOS';
      const rateValue = Number(line.rate || rawValue.amount || '0') || 0;
      const amountValue = Number(line.amount || voucherAmount || '0') || 0;

      const entry: Record<string, unknown> = {
        STOCKITEMNAME: stockItemName,
        ACTUALQTY: `${quantityValue} ${unit}`.trim(),
        BILLEDQTY: `${quantityValue} ${unit}`.trim(),
        RATE: `${rateValue.toFixed(2)}/${unit}`,
        // Keep operator-entered amounts positive in the UI payload.
        // The connector applies Tally-specific sign conventions during import.
        AMOUNT: Math.abs(amountValue).toFixed(2),
      };
      if ((this.isSalesVoucherType(normalizedType) || this.isPurchaseVoucherType(normalizedType)) && accountingLedgerName) {
        entry['ACCOUNTINGLEDGERNAME'] = accountingLedgerName;
      }
      return entry;
    });
  }

  private buildImportXmlEnvelope(
    endpoint: VoucherEndpointOption,
    rawValue: Partial<{
      company: string | null;
      date: string | null;
      voucherTypeName: string | null;
      voucherNumber: string | null;
      partyLedgerName: string | null;
      offsetLedgerName: string | null;
      narration: string | null;
      amount: string | null;
      action: string | null;
    }>
  ): string {
    const company = this.escapeXml(rawValue.company || this.currentCompanyName);
    const date = this.escapeXml(this.resolveSubmissionVoucherDate(rawValue.date));
    const voucherType = this.escapeXml(rawValue.voucherTypeName || endpoint.defaultVoucherType);
    const voucherNumber = this.escapeXml(rawValue.voucherNumber || this.lastAutoVoucherNumber || '1');
    const narration = this.escapeXml(rawValue.narration || 'Created from tally-ui');
    const action = this.escapeXml(rawValue.action || 'Create');
    const preset = this.resolveLedgerPreset(rawValue.voucherTypeName || endpoint.defaultVoucherType);
    const partyLedgerName = this.escapeXml(rawValue.partyLedgerName || preset.partyLedgerName);
    const offsetLedgerName = this.escapeXml(rawValue.offsetLedgerName || preset.offsetLedgerName);
    const absoluteAmount = Math.abs(Number(rawValue.amount || '1000.00') || 0).toFixed(2);
    const negativeAmount = `-${absoluteAmount}`;

    return `<ENVELOPE>
<HEADER>
<VERSION>1</VERSION>
<TALLYREQUEST>Import</TALLYREQUEST>
<TYPE>Data</TYPE>
<ID>Vouchers</ID>
</HEADER>
<BODY>
<DESC>
<STATICVARIABLES>
<SVCURRENTCOMPANY>${company}</SVCURRENTCOMPANY>
</STATICVARIABLES>
</DESC>
<DATA>
<TALLYMESSAGE>
<VOUCHER VCHTYPE="${voucherType}" ACTION="${action}">
<DATE>${date}</DATE>
<EFFECTIVEDATE>${date}</EFFECTIVEDATE>
<VOUCHERTYPENAME>${voucherType}</VOUCHERTYPENAME>
<VOUCHERNUMBER>${voucherNumber}</VOUCHERNUMBER>
<NARRATION>${narration}</NARRATION>
<ALLLEDGERENTRIES.LIST>
<LEDGERNAME>${partyLedgerName}</LEDGERNAME>
<AMOUNT>${absoluteAmount}</AMOUNT>
</ALLLEDGERENTRIES.LIST>
<ALLLEDGERENTRIES.LIST>
<LEDGERNAME>${offsetLedgerName}</LEDGERNAME>
<AMOUNT>${negativeAmount}</AMOUNT>
</ALLLEDGERENTRIES.LIST>
</VOUCHER>
</TALLYMESSAGE>
</DATA>
</BODY>
</ENVELOPE>`;
  }

  private buildLedgerEntries(
    voucherType: string,
    partyLedgerName: string,
    offsetLedgerName: string,
    amount: number,
    rawValue: Record<string, any>
  ): Array<Record<string, string>> {
    const absoluteAmount = Math.abs(amount || 0);
    const negativeAmount = `-${absoluteAmount.toFixed(2)}`;
    const normalizedType = voucherType.trim().toLowerCase();
    const taxableTotal = Number(this.inventorySubtotal);
    const taxTotal = Number(this.inventoryTaxTotal);
    const freightAmount = Number(rawValue.freightAmount || '0');
    const roundOffAmount = Number(rawValue.roundOffAmount || '0');
    const taxLedgerName = rawValue.taxLedgerName || this.taxLedgerLabel;
    const chargeEntries: Array<Record<string, string>> = [];

    if (taxTotal > 0) {
      chargeEntries.push({ LEDGERNAME: taxLedgerName, AMOUNT: `-${taxTotal.toFixed(2)}`, ISDEEMEDPOSITIVE: 'Yes' });
    }
    if (freightAmount > 0) {
      chargeEntries.push({ LEDGERNAME: rawValue.freightLedgerName || 'Freight Charges', AMOUNT: `-${freightAmount.toFixed(2)}`, ISDEEMEDPOSITIVE: 'Yes' });
    }
    if (roundOffAmount !== 0) {
      chargeEntries.push({ LEDGERNAME: rawValue.roundOffLedgerName || 'Round Off', AMOUNT: `${roundOffAmount < 0 ? '' : '-'}${Math.abs(roundOffAmount).toFixed(2)}`, ISDEEMEDPOSITIVE: 'Yes' });
    }

    if (
      normalizedType === 'purchase' ||
      normalizedType === 'purchase order' ||
      normalizedType === 'debit note' ||
      normalizedType === 'receipt note' ||
      normalizedType === 'job work in order'
    ) {
      return [
        { LEDGERNAME: offsetLedgerName, AMOUNT: taxableTotal > 0 ? taxableTotal.toFixed(2) : absoluteAmount.toFixed(2) },
        ...chargeEntries.map((entry) => ({ ...entry, AMOUNT: entry.AMOUNT.startsWith('-') ? entry.AMOUNT.slice(1) : entry.AMOUNT })),
        { LEDGERNAME: partyLedgerName, AMOUNT: negativeAmount, ISDEEMEDPOSITIVE: 'Yes', ISPARTYLEDGER: 'Yes' },
      ];
    }

    if (
      normalizedType === 'sales' ||
      normalizedType === 'sales order' ||
      normalizedType === 'credit note' ||
      normalizedType === 'delivery note' ||
      normalizedType === 'job work out order'
    ) {
      return [
        { LEDGERNAME: partyLedgerName, AMOUNT: absoluteAmount.toFixed(2), ISPARTYLEDGER: 'Yes' },
        { LEDGERNAME: offsetLedgerName, AMOUNT: `-${(taxableTotal > 0 ? taxableTotal : absoluteAmount).toFixed(2)}`, ISDEEMEDPOSITIVE: 'Yes' },
        ...chargeEntries,
      ];
    }

    return [
      { LEDGERNAME: partyLedgerName, AMOUNT: absoluteAmount.toFixed(2) },
      { LEDGERNAME: offsetLedgerName, AMOUNT: negativeAmount, ISDEEMEDPOSITIVE: 'Yes' },
    ];
  }

  private resolveEffectiveOffsetLedgerName(normalizedType: string, currentValue: string): string {
    const trimmedValue = currentValue.trim();
    if (this.isSalesVoucherType(normalizedType)) {
      if (trimmedValue && !this.isCashOrBankLedger(trimmedValue)) {
        return trimmedValue;
      }
      return this.resolvePreferredSalesLedgerName(trimmedValue);
    }
    if (this.isPurchaseVoucherType(normalizedType)) {
      if (trimmedValue && !this.isCashOrBankLedger(trimmedValue)) {
        return trimmedValue;
      }
      return this.resolvePreferredPurchaseLedgerName(trimmedValue);
    }
    if (!this.isSalesVoucherType(normalizedType)) {
      return trimmedValue;
    }
    return trimmedValue;
  }

  private resolvePreferredSalesLedgerName(fallbackValue = ''): string {
    const preferredCandidates = ['Sales A/C', 'Sales Account', 'Sales', 'Sales Accounts'];
    for (const candidate of preferredCandidates) {
      if (this.ledgerOptions.includes(candidate)) {
        return candidate;
      }
    }
    const discoveredSalesLedger = this.ledgerOptions.find((ledger) => {
      const normalized = ledger.trim().toLowerCase();
      return normalized.includes('sales') && !this.isCashOrBankLedger(ledger);
    });
    if (discoveredSalesLedger) {
      return discoveredSalesLedger;
    }
    return fallbackValue;
  }

  private resolvePreferredPurchaseLedgerName(fallbackValue = ''): string {
    const preferredCandidates = ['Purchase A/C', 'Purchase Account', 'Purchase', 'Purchase Accounts'];
    for (const candidate of preferredCandidates) {
      if (this.ledgerOptions.includes(candidate)) {
        return candidate;
      }
    }
    const discoveredPurchaseLedger = this.ledgerOptions.find((ledger) => {
      const normalized = ledger.trim().toLowerCase();
      return normalized.includes('purchase') && !this.isCashOrBankLedger(ledger);
    });
    if (discoveredPurchaseLedger) {
      return discoveredPurchaseLedger;
    }
    return fallbackValue;
  }

  private isCashOrBankLedger(ledgerName: string | null | undefined): boolean {
    const normalized = (ledgerName || '').trim().toLowerCase();
    return normalized === 'cash' || normalized === 'bank' || normalized === 'bank a/c';
  }

  private isSalesVoucherType(normalizedType: string): boolean {
    return [
      'sales',
      'sales order',
      'credit note',
      'delivery note',
      'job work out order',
    ].includes(normalizedType);
  }

  private isPurchaseVoucherType(normalizedType: string): boolean {
    return [
      'purchase',
      'purchase order',
      'debit note',
      'receipt note',
      'job work in order',
    ].includes(normalizedType);
  }

  private resolveLedgerPreset(voucherType: string): { partyLedgerName: string; offsetLedgerName: string } {
    const normalizedType = voucherType.trim().toLowerCase();

    if (
      normalizedType === 'purchase' ||
      normalizedType === 'purchase order' ||
      normalizedType === 'debit note' ||
      normalizedType === 'receipt note' ||
      normalizedType === 'job work in order'
    ) {
      return { partyLedgerName: 'Supplier 1', offsetLedgerName: 'Purchase A/C' };
    }
    if (
      normalizedType === 'sales' ||
      normalizedType === 'sales order' ||
      normalizedType === 'credit note' ||
      normalizedType === 'delivery note' ||
      normalizedType === 'job work out order'
    ) {
      return { partyLedgerName: 'Customer 1', offsetLedgerName: 'Sales A/C' };
    }
    if (normalizedType === 'receipt') {
      return { partyLedgerName: 'Bank', offsetLedgerName: 'Cash' };
    }
    if (
      normalizedType === 'payment' ||
      normalizedType === 'contra' ||
      normalizedType === 'reversing journal' ||
      normalizedType === 'journal' ||
      normalizedType === 'payroll' ||
      normalizedType === 'physical stock' ||
      normalizedType === 'memorandum' ||
      normalizedType === 'stock journal' ||
      normalizedType === 'stock journal'
    ) {
      return { partyLedgerName: 'Cash', offsetLedgerName: 'Bank' };
    }

    return { partyLedgerName: 'Cash', offsetLedgerName: 'Bank' };
  }

  private isPartyVoucherType(normalizedType: string): boolean {
    return [
      'sales',
      'purchase',
      'sales order',
      'purchase order',
      'debit note',
      'credit note',
      'delivery note',
      'receipt note',
      'job work in order',
      'job work out order',
    ].includes(normalizedType);
  }

  private usesLedgerEntries(normalizedType: string): boolean {
    return [
      'sales',
      'purchase',
      'sales order',
      'purchase order',
      'debit note',
      'credit note',
      'delivery note',
      'receipt note',
      'job work in order',
      'job work out order',
      'reversing journal',
      'journal',
      'payment',
      'receipt',
      'contra',
      'payroll',
      'physical stock',
      'memorandum',
    ].includes(normalizedType);
  }

  private usesInventoryEntries(normalizedType: string): boolean {
    return [
      'sales',
      'purchase',
      'sales order',
      'purchase order',
      'debit note',
      'credit note',
      'delivery note',
      'receipt note',
      'job work in order',
      'job work out order',
    ].includes(normalizedType);
  }

  private applySelectedStockItemDefaults(): void {
    const stockItemName = this.currentInventoryLine?.stockItemName || this.form.controls.stockItemName.value || '';
    if (!stockItemName) {
      return;
    }
    const selectedItem = this.stockItemOptions.find((item) => item.name === stockItemName);
    if (!selectedItem) {
      return;
    }
    const currentLine = this.currentInventoryLine || this.createEmptyInventoryLine();
    this.inventoryLines[this.activeInventoryLineIndex] = {
      ...currentLine,
      stockItemName: selectedItem.name,
      description: selectedItem.name,
      unit: selectedItem.baseUnits || currentLine.unit || 'NOS',
      hsnCode: selectedItem.hsnCode || '',
      rate: selectedItem.rate || currentLine.rate || '0.00',
      amount: this.calculateInventoryLineAmount(currentLine.quantity || '1', selectedItem.rate || currentLine.rate || '0.00', currentLine.discount),
    };
    this.syncFormWithInventoryLine(this.inventoryLines[this.activeInventoryLineIndex]);
  }

  private ensureInventorySelection(): void {
    const currentLine = this.currentInventoryLine;
    if (!currentLine) {
      return;
    }
    if (!currentLine.stockItemName || !this.hasStockItemOption(currentLine.stockItemName)) {
      this.syncFormWithInventoryLine(currentLine);
    }
  }

  private ensureInventoryLinesHaveSelections(): void {
    this.inventoryLines = this.inventoryLines.map((line) => ({
      ...line,
      quantity: line.quantity || '1',
      unit: line.unit || 'NOS',
      rate: line.rate || '0.00',
      amount: line.stockItemName
        ? line.amount || this.calculateInventoryLineAmount(line.quantity || '1', line.rate || '0.00', line.discount)
        : '0.00',
    }));
  }

  private syncInventoryLinesWithStockOptions(): void {
    if (!this.stockItemOptions.length || !this.inventoryLines.length) {
      return;
    }
    this.inventoryLines = this.inventoryLines.map((line) => {
      const selectedItem = this.stockItemOptions.find(
        (item) => item.name === line.stockItemName || item.name === line.description
      );
      if (!selectedItem) {
        return line;
      }
      return {
        ...line,
        stockItemName: selectedItem.name,
        description: selectedItem.name,
        unit: selectedItem.baseUnits || line.unit || 'NOS',
        hsnCode: selectedItem.hsnCode || '',
        rate: selectedItem.rate || line.rate || '0.00',
        amount: this.calculateInventoryLineAmount(line.quantity || '1', selectedItem.rate || line.rate || '0.00', line.discount),
      };
    });
  }

  private syncLedgerSelectionsWithOptions(): void {
    if (!this.ledgerOptions.length) {
      return;
    }

    const voucherType = this.form.controls.voucherTypeName.value || this.selectedEndpoint.defaultVoucherType || '';
    const normalizedType = voucherType.trim().toLowerCase();
    const nextPartyLedger = this.resolveLedgerSelection(
      this.form.controls.partyLedgerName.value,
      this.resolvePartyLedgerCandidates(normalizedType)
    );
    const nextOffsetLedger = this.resolveLedgerSelection(
      this.form.controls.offsetLedgerName.value,
      this.resolveOffsetLedgerCandidates(normalizedType)
    );

    this.form.patchValue({
      partyLedgerName: nextPartyLedger,
      offsetLedgerName: nextOffsetLedger,
      buyerLegalName: nextPartyLedger || this.form.controls.buyerLegalName.value,
      buyerTradeName: nextPartyLedger || this.form.controls.buyerTradeName.value,
    });
  }

  private resolvePartyLedgerCandidates(normalizedType: string): string[] {
    if (normalizedType === 'purchase' || normalizedType === 'purchase order' || normalizedType === 'debit note' || normalizedType === 'receipt note' || normalizedType === 'job work in order') {
      return ['Supplier 1', 'Raj traders', 'karco', 'NANTHA'];
    }

    if (normalizedType === 'sales' || normalizedType === 'sales order' || normalizedType === 'credit note' || normalizedType === 'delivery note' || normalizedType === 'job work out order') {
      return ['Customer 1', 'Raj traders', 'karco', 'NANTHA'];
    }

    if (normalizedType === 'receipt') {
      return ['Bank', 'Bank A/C', 'Cash'];
    }

    return ['Cash', 'Bank', 'Bank A/C'];
  }

  private resolveOffsetLedgerCandidates(normalizedType: string): string[] {
    if (normalizedType === 'purchase' || normalizedType === 'purchase order' || normalizedType === 'debit note' || normalizedType === 'receipt note' || normalizedType === 'job work in order') {
      return ['Purchase A/C', 'Purchase Account', 'Purchase'];
    }

    if (normalizedType === 'sales' || normalizedType === 'sales order' || normalizedType === 'credit note' || normalizedType === 'delivery note' || normalizedType === 'job work out order') {
      return ['Sales A/C', 'Sales Account', 'Sales'];
    }

    if (normalizedType === 'receipt') {
      return ['Cash', 'Bank', 'Bank A/C'];
    }

    return ['Bank', 'Bank A/C', 'Cash'];
  }

  private resolveLedgerSelection(currentValue: string | null | undefined, candidates: string[]): string {
    const normalizedCurrent = (currentValue || '').trim();
    if (normalizedCurrent && this.ledgerOptions.includes(normalizedCurrent)) {
      return normalizedCurrent;
    }

    for (const candidate of candidates) {
      const normalizedCandidate = candidate.trim();
      if (normalizedCandidate && this.ledgerOptions.includes(normalizedCandidate)) {
        return normalizedCandidate;
      }
    }

    const firstBusinessLedger = this.ledgerOptions.find((ledger) => !this.isSystemLedger(ledger));
    return firstBusinessLedger || this.ledgerOptions[0] || '';
  }

  private filterLedgerOptions(value: string | null | undefined): string[] {
    const query = (value || '').trim().toLowerCase();
    if (!query) {
      return this.ledgerOptions;
    }
    return this.ledgerOptions.filter((ledger) => ledger.toLowerCase().includes(query));
  }

  private getCurrentLookupOptionsCount(): number {
    return this.getCurrentLookupOptions().length;
  }

  private getCurrentLookupOptions(): string[] {
    if (this.activeLookupPanel === 'partyLedgerName') {
      return this.filteredPartyLedgerOptions;
    }
    if (this.activeLookupPanel === 'offsetLedgerName') {
      return this.filteredOffsetLedgerOptions;
    }
    if (this.activeLookupPanel === 'stockItemName') {
      return this.filteredStockItemOptions.map((item) => item.name);
    }
    return [];
  }

  private commitHighlightedLookupOption(
    panel: 'partyLedgerName' | 'offsetLedgerName' | 'stockItemName',
    stockLineIndex?: number
  ): void {
    const options = this.getCurrentLookupOptions();
    if (!options.length) {
      return;
    }

    const targetIndex = this.activeLookupIndex >= 0 ? this.activeLookupIndex : 0;
    const selectedValue = options[targetIndex];
    if (!selectedValue) {
      return;
    }

    if (panel === 'partyLedgerName' || panel === 'offsetLedgerName') {
      this.selectLedgerOption(panel, selectedValue);
      return;
    }

    this.selectStockItemOption(stockLineIndex ?? this.activeInventoryLineIndex, selectedValue);
  }

  private hasStockItemOption(stockItemName: string): boolean {
    const normalizedName = (stockItemName || '').trim();
    return !!normalizedName && this.stockItemOptions.some((item) => item.name === normalizedName);
  }

  private extractStockItemOptions(response: any): StockItemOption[] {
    const rawStockItems = this.toArray(response?.data || response?.STOCKITEM);
    const normalizedOptions = rawStockItems
      .map((item) => ({
        name: this.readText(item, 'NAME', 'name'),
        baseUnits: this.readText(item, 'BASEUNITS', 'base_units', 'BASEUNIT', 'baseUnit'),
        hsnCode: this.readText(item, 'HSNCODE', 'hsn_code', 'HSN', 'hsn'),
        quantity: this.readText(
          item,
          'CLOSINGBALANCE',
          'closing_balance',
          'CLOSINGQTY',
          'closing_qty',
          'QUANTITY',
          'quantity'
        ),
        rate: this.readText(item, 'RATEPER', 'rate_per', 'RATE', 'rate'),
        value: this.readText(item, 'OPENINGVALUE', 'opening_value', 'VALUE', 'value'),
      }))
      .filter((item) => !!item.name);

    const filteredOptions = normalizedOptions.filter((item) => this.isDisplayableStockItem(item.name));
    const visibleOptions = filteredOptions.length ? filteredOptions : normalizedOptions;

    return visibleOptions.sort((left, right) => left.name.localeCompare(right.name));
  }

  private readCachedLedgers(company: string): string[] {
    return this.readMasterCache<string[]>(this.buildMasterCacheKey('ledgers', company), []);
  }

  private writeCachedLedgers(company: string, ledgers: string[]): void {
    this.writeMasterCache(this.buildMasterCacheKey('ledgers', company), ledgers);
  }

  private readCachedStockItems(company: string): StockItemOption[] {
    return this.readMasterCache<StockItemOption[]>(this.buildMasterCacheKey('stock-items', company), []);
  }

  private writeCachedStockItems(company: string, stockItems: StockItemOption[]): void {
    this.writeMasterCache(this.buildMasterCacheKey('stock-items', company), stockItems);
  }

  private buildMasterCacheKey(kind: 'ledgers' | 'stock-items', company: string): string {
    return `voucher-create.${kind}.${(company || 'default').trim().toLowerCase()}`;
  }

  private readMasterCache<T>(key: string, fallbackValue: T): T {
    try {
      const rawValue = localStorage.getItem(key);
      return rawValue ? (JSON.parse(rawValue) as T) : fallbackValue;
    } catch {
      return fallbackValue;
    }
  }

  private writeMasterCache(key: string, value: unknown): void {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch {
      // Ignore storage failures and continue with in-memory values.
    }
  }

  private isDisplayableStockItem(stockItemName: string): boolean {
    const normalizedName = stockItemName.trim().toLowerCase();
    if (!normalizedName) {
      return false;
    }

    return ![
      /^e2e_/,
      /^e2e-/,
      /^e2eitem/,
      /^e2e_item/,
      /^sync test\b/,
      /^zz_sync_/,
      /^codex_/,
      /^test\b/,
      /^demo\b/,
    ].some((pattern) => pattern.test(normalizedName));
  }

  private isSystemLedger(ledgerName: string): boolean {
    const normalizedName = ledgerName.trim().toLowerCase();
    return [
      'cash',
      'bank',
      'bank a/c',
      'sales account',
      'purchase',
      'profit & loss a/c',
      'unknown',
    ].includes(normalizedName);
  }

  private resolveStockItemBaseUnits(stockItemName: string): string {
    return this.stockItemOptions.find((item) => item.name === stockItemName)?.baseUnits || '';
  }

  private syncInventoryAmount(): void {
    this.inventoryLines = this.inventoryLines.map((line) => ({
      ...line,
      amount: this.calculateInventoryLineAmount(line.quantity, line.rate, line.discount),
    }));
    const activeLine = this.currentInventoryLine;
    if (activeLine) {
      this.syncFormWithInventoryLine(activeLine);
    }
    this.form.patchValue({
      amount: this.inventorySubtotal,
      itemTaxableAmount: activeLine?.amount || this.inventorySubtotal,
    });
  }

  private getEffectiveInventoryLines(rawValue: Record<string, any>): InventoryLine[] {
    const sourceLines = this.inventoryLines.filter((line) => this.isMeaningfulInventoryLine(line));
    if (sourceLines.length) {
      return sourceLines;
    }
    const fallbackLine = this.createEmptyInventoryLine({
      stockItemName: rawValue.stockItemName || '',
      description: rawValue.itemDescription || '',
      hsnCode: rawValue.itemHsnCode || '',
      quantity: rawValue.itemQuantity || '1',
      unit: rawValue.itemUnit || 'NOS',
      rate: rawValue.itemRate || rawValue.amount || '1000.00',
      gstRate: rawValue.itemGstRate || '18',
      discount: rawValue.itemDiscount || '0',
      amount: rawValue.itemTaxableAmount || rawValue.amount || '1000.00',
    });
    return this.isMeaningfulInventoryLine(fallbackLine) ? [fallbackLine] : [];
  }

  private validateInventoryVoucher(): string {
    const rawValue = this.form.getRawValue();
    const lines = this.getEffectiveInventoryLines(rawValue);
    if (!lines.length) {
      return 'Select a stock item and enter quantity/rate before creating the voucher.';
    }
    const hasInvalidLine = lines.some((line) => !(line.stockItemName || line.description || '').trim() || (Number(line.amount || '0') || 0) <= 0);
    if (hasInvalidLine) {
      return 'Each inventory line must have a stock item and a positive amount.';
    }
    if (this.isSalesVoucherType((this.form.controls.voucherTypeName.value || '').trim().toLowerCase())) {
      for (const line of lines) {
        const stockWarning = this.getInventoryLineStockWarning(line);
        if (stockWarning) {
          return stockWarning;
        }
      }
    }
    return '';
  }

  getSelectedStockAvailabilityMessage(line: InventoryLine | null | undefined): string {
    if (!line) {
      return '';
    }
    return this.getInventoryLineStockWarning(line);
  }

  getInventoryLineStockAvailabilityLabel(line: InventoryLine | null | undefined): string {
    if (!line) {
      return 'Pending';
    }
    const itemName = (line.stockItemName || line.description || '').trim();
    if (!itemName) {
      return 'Pending';
    }
    const selectedItem = this.stockItemOptions.find((item) => item.name === itemName);
    return this.formatStockAvailabilityLabel(selectedItem || null);
  }

  private getInventoryLineStockWarning(line: InventoryLine): string {
    const normalizedType = (this.form.controls.voucherTypeName.value || '').trim().toLowerCase();
    if (!this.isSalesVoucherType(normalizedType)) {
      return '';
    }
    const itemName = (line.stockItemName || line.description || '').trim();
    if (!itemName) {
      return '';
    }
    const selectedItem = this.stockItemOptions.find((item) => item.name === itemName);
    if (!selectedItem) {
      return '';
    }
    const availableQuantity = this.parseStockQuantityValue(selectedItem.quantity);
    if (availableQuantity <= 0) {
      return `Selected product is out of stock: ${selectedItem.name}.`;
    }
    const requestedQuantity = Number(line.quantity || '0') || 0;
    if (requestedQuantity > availableQuantity) {
      return `Only ${this.formatStockQuantity(availableQuantity)} ${selectedItem.baseUnits || ''}`.trim()
        + ` available for ${selectedItem.name}.`;
    }
    return '';
  }

  private notifyOutOfStockSelection(stockItemName: string): void {
    const normalizedType = (this.form.controls.voucherTypeName.value || '').trim().toLowerCase();
    if (!this.isSalesVoucherType(normalizedType)) {
      return;
    }
    const selectedItem = this.stockItemOptions.find((item) => item.name === stockItemName);
    if (!selectedItem) {
      return;
    }
    const availableQuantity = this.parseStockQuantityValue(selectedItem.quantity);
    if (availableQuantity <= 0) {
      const message = 'Selected product is out of stock.';
      this.errorMessage = message;
      window.alert(message);
    }
  }

  private formatStockAvailabilityLabel(item: StockItemOption | null): string {
    if (!item) {
      return 'Pending';
    }
    const availableQuantity = Math.max(0, this.parseStockQuantityValue(item.quantity));
    const quantityText = this.formatStockQuantity(availableQuantity);
    const unitText = (item.baseUnits || '').trim();
    return `${quantityText}${unitText ? ` ${unitText}` : ''}`;
  }

  private parseStockQuantityValue(quantityText: string | null | undefined): number {
    const normalized = `${quantityText || ''}`.trim();
    if (!normalized) {
      return 0;
    }
    const match = normalized.replace(/,/g, '').match(/-?\d+(?:\.\d+)?/);
    if (!match) {
      return 0;
    }
    const parsed = Number(match[0]);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  private formatStockQuantity(quantity: number): string {
    if (!Number.isFinite(quantity)) {
      return '0';
    }
    return Number.isInteger(quantity) ? `${quantity}` : quantity.toFixed(2);
  }

  private isMeaningfulInventoryLine(line: InventoryLine): boolean {
    const itemName = (line.stockItemName || line.description || '').trim();
    const amount = Number(line.amount || '0') || 0;
    const quantity = Number(line.quantity || '0') || 0;
    const rate = Number(line.rate || '0') || 0;
    return !!itemName && amount > 0 && quantity > 0 && rate >= 0;
  }

  private syncFormWithInventoryLine(line: InventoryLine): void {
    this.form.patchValue({
      stockItemName: line.stockItemName,
      itemDescription: line.description,
      itemHsnCode: line.hsnCode,
      itemQuantity: line.quantity,
      itemUnit: line.unit,
      itemRate: line.rate,
      itemGstRate: line.gstRate,
      itemDiscount: line.discount,
      itemTaxableAmount: line.amount,
      amount: this.showInventoryVoucherSection ? this.inventorySubtotal || line.amount : line.amount,
    });
  }

  private createEmptyInventoryLine(overrides: Partial<InventoryLine> = {}): InventoryLine {
    const quantity = overrides.quantity || '1';
    const rate = overrides.rate || '0.00';
    return {
      stockItemName: '',
      description: '',
      hsnCode: '',
      quantity,
      unit: overrides.unit || 'NOS',
      rate,
      gstRate: overrides.gstRate || '18',
      discount: overrides.discount || '0',
      amount: overrides.amount || this.calculateInventoryLineAmount(quantity, rate, overrides.discount || '0'),
      ...overrides,
    };
  }

  private calculateInventoryLineAmount(quantity: string, rate: string, discount: string = '0'): string {
    const quantityValue = Number(quantity || '0');
    const rateValue = Number(rate || '0');
    const discountValue = Number(discount || '0');
    if (!Number.isFinite(quantityValue) || !Number.isFinite(rateValue) || !Number.isFinite(discountValue)) {
      return '0.00';
    }
    const grossValue = quantityValue * rateValue;
    const netValue = grossValue - (grossValue * discountValue) / 100;
    return netValue.toFixed(2);
  }

  private moveInventoryFocus(
    index: number,
    field: 'stockItemName' | 'quantity' | 'unit' | 'rate' | 'discount' | 'gstRate',
    direction: 'next' | 'up' | 'down'
  ): void {
    const fieldOrder: Array<'stockItemName' | 'quantity' | 'unit' | 'rate' | 'discount' | 'gstRate'> = ['stockItemName', 'quantity', 'unit', 'rate', 'discount', 'gstRate'];
    const fieldIndex = fieldOrder.indexOf(field);
    let targetRow = index;
    let targetFieldIndex = fieldIndex;

    if (direction === 'next') {
      if (fieldIndex < fieldOrder.length - 1) {
        targetFieldIndex += 1;
      } else if (index < this.inventoryLines.length - 1) {
        targetRow += 1;
        targetFieldIndex = 0;
      } else {
        this.addInventoryLine();
        targetRow = this.inventoryLines.length - 1;
        targetFieldIndex = 0;
      }
    } else if (direction === 'down' && index < this.inventoryLines.length - 1) {
      targetRow += 1;
    } else if (direction === 'up' && index > 0) {
      targetRow -= 1;
    } else {
      return;
    }

    const targetField = fieldOrder[targetFieldIndex];
    this.focusInventoryCell(targetRow, targetField);
  }

  private focusInventoryCell(index: number, field: 'stockItemName' | 'quantity' | 'unit' | 'rate' | 'discount' | 'gstRate'): void {
    this.setActiveInventoryLine(index);
    setTimeout(() => {
      const target = document.querySelector<HTMLElement>(`[data-line-index="${index}"][data-line-field="${field}"]`);
      target?.focus();
    });
  }

  private resolveError(error: HttpErrorResponse): string {
    const parsedError = this.parseJsonErrorBody(error.error);
    const duplicateVoucherConflict = this.extractDuplicateVoucherConflict(error, parsedError);

    if (error.status === 401) {
      return 'Your login session expired. Please sign in again and resend the voucher.';
    }
    if (duplicateVoucherConflict) {
      const voucherNumberText = duplicateVoucherConflict.voucherNumber ? ` ${duplicateVoucherConflict.voucherNumber}` : '';
      const companyText = duplicateVoucherConflict.company ? ` for ${duplicateVoucherConflict.company}` : '';
      return `Voucher number${voucherNumberText} already exists${companyText}. A new voucher number has been suggested below.`;
    }
    if (parsedError && parsedError['error_type'] === 'tally_connection_unavailable') {
      return 'Tally listener on 127.0.0.1:9000 is not reachable. Open TallyPrime, open the company, and enable XML/HTTP before retrying this voucher.';
    }
    if (parsedError && typeof parsedError.detail === 'string') {
      return parsedError.detail;
    }
    if (parsedError && typeof parsedError.reason === 'string') {
      return parsedError.reason;
    }
    if (parsedError && typeof parsedError.detail === 'object' && parsedError.detail) {
      const detail = parsedError.detail as Record<string, unknown>;
      if (typeof detail.reason === 'string') {
        return detail.reason;
      }
      if (typeof detail.message === 'string') {
        return detail.message;
      }
    }
    if (error.error && typeof error.error === 'object' && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    if (error.error && typeof error.error === 'object' && typeof error.error.reason === 'string') {
      return error.error.reason;
    }
    if (error.error && typeof error.error === 'object' && typeof error.error.detail === 'object') {
      const detail = error.error.detail as Record<string, unknown>;
      if (typeof detail.reason === 'string') {
        return detail.reason;
      }
    }
    if (error.error && typeof error.error === 'object' && typeof error.error.message === 'string') {
      return error.error.message;
    }
    if (typeof error.error === 'string' && error.error.trim()) {
      return error.error.trim();
    }
    if (error.status === 0) {
      return 'The voucher request could not reach the backend. Refresh the page once and try again.';
    }
    if (error.status === 404) {
      return 'The voucher create API route was not found on the backend. Refresh the app once and retry.';
    }
    if (error.status === 400) {
      return 'The backend rejected the voucher request as invalid. Check the response panel for the exact validation detail.';
    }
    if (error.status === 409) {
      return 'The voucher request conflicts with existing data. Review the response detail and retry with corrected values.';
    }
    if (error.status >= 500) {
      return `The backend failed while processing the voucher request (HTTP ${error.status}${error.statusText ? ` ${error.statusText}` : ''}). Check the backend log and retry.`;
    }
    if (error.message) {
      return error.message;
    }
    return `The voucher request failed unexpectedly${error.status ? ` (HTTP ${error.status})` : ''}. Check the response panel and backend log, then retry.`;
  }

  private resolveResponse(error: HttpErrorResponse): string {
    const parsedError = this.parseJsonErrorBody(error.error);
    if (parsedError) {
      return JSON.stringify(parsedError, null, 2);
    }
    if (typeof error.error === 'string') {
      return error.error;
    }
    if (error.error) {
      return JSON.stringify(error.error, null, 2);
    }
    return '';
  }

  private parseJsonErrorBody(errorBody: unknown): Record<string, unknown> | null {
    if (errorBody && typeof errorBody === 'object' && !Array.isArray(errorBody)) {
      return errorBody as Record<string, unknown>;
    }
    if (typeof errorBody !== 'string') {
      return null;
    }
    const trimmed = errorBody.trim();
    if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
      return null;
    }
    try {
      const parsed = JSON.parse(trimmed);
      return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null;
    } catch {
      return null;
    }
  }

  private parseJsonRecord(responseText: string): Record<string, unknown> | null {
    const trimmed = (responseText || '').trim();
    if (!trimmed.startsWith('{')) {
      return null;
    }
    try {
      const parsed = JSON.parse(trimmed);
      return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null;
    } catch {
      return null;
    }
  }

  private handleDuplicateVoucherError(error: HttpErrorResponse): void {
    const duplicateVoucherConflict = this.extractDuplicateVoucherConflict(error, this.parseJsonErrorBody(error.error));
    if (!duplicateVoucherConflict || !this.isVoucherEndpoint) {
      return;
    }

    this.form.controls.voucherNumber.setErrors({ duplicate: true });
    this.form.controls.voucherNumber.markAsTouched();
    this.generateAndApplyNextVoucherNumber(() => {
      const nextVoucherNumber = (this.form.controls.voucherNumber.value || '').trim();
      if (nextVoucherNumber && nextVoucherNumber !== duplicateVoucherConflict.voucherNumber) {
        this.errorMessage = `${this.errorMessage} Suggested next voucher number: ${nextVoucherNumber}.`;
      }
    }, true);
  }

  private extractDuplicateVoucherConflict(
    error: HttpErrorResponse,
    parsedError: Record<string, unknown> | null
  ): { voucherNumber: string; company: string } | null {
    if (parsedError && parsedError['detail'] === 'duplicate_voucher_number') {
      return {
        voucherNumber: this.readTextValue(parsedError['voucher_number']),
        company: this.readTextValue(parsedError['company']),
      };
    }

    const rawError = typeof error.error === 'string' ? error.error : '';
    const combinedText = [
      typeof parsedError?.['message'] === 'string' ? parsedError['message'] : '',
      typeof parsedError?.['reason'] === 'string' ? parsedError['reason'] : '',
      rawError,
      error.message || '',
    ]
      .filter((value) => typeof value === 'string' && value.trim())
      .join(' ');

    if (!/uq_tally_voucher_write_queue_voucher_number|Duplicate entry/i.test(combinedText)) {
      return null;
    }

    const duplicateMatch = combinedText.match(/Duplicate entry '([^']+)-([^'-]+)' for key 'uq_tally_voucher_write_queue_voucher_number'/i);
    if (duplicateMatch) {
      return {
        company: duplicateMatch[1].trim(),
        voucherNumber: duplicateMatch[2].trim(),
      };
    }

    return {
      voucherNumber: (this.form.controls.voucherNumber.value || '').trim(),
      company: (this.form.controls.company.value || this.currentCompanyName || '').trim(),
    };
  }

  private readTextValue(value: unknown): string {
    return typeof value === 'string' ? value.trim() : '';
  }

  private shouldStoreVoucherOffline(error: HttpErrorResponse, parsedError: Record<string, unknown> | null): boolean {
    if (!this.isVoucherEndpoint) {
      return false;
    }
    if (parsedError && parsedError['queued'] === true) {
      return false;
    }
    if (error.status === 0) {
      return true;
    }
    if (parsedError && parsedError['error_type'] === 'tally_connection_unavailable') {
      return true;
    }
    if (parsedError && typeof parsedError.detail === 'object' && parsedError.detail) {
      const detail = parsedError.detail as Record<string, unknown>;
      if (detail['error_type'] === 'tally_connection_unavailable') {
        return true;
      }
    }
    return false;
  }

  private storeVoucherOffline(
    request: { path: string; action?: string; contentType: 'application/json' | 'application/xml'; payload: unknown; company?: string },
    endpointLabel: string,
    originalError: HttpErrorResponse,
    parsedError: Record<string, unknown> | null
  ): void {
    const requestBody = typeof request.payload === 'string' ? request.payload : JSON.stringify(request.payload);
    this.vouchersApiService.storeVoucherOffline({
      path: request.path.replace(/^\/api/, ''),
      action: request.action,
      company: request.company,
      contentType: request.contentType,
      requestBody,
      reason: this.resolveError(originalError),
    }).subscribe({
      next: (response) => {
        this.isSubmitting = false;
        this.errorMessage = '';
        const responseText = typeof response === 'string' ? response : JSON.stringify(response, null, 2);
        const parsedResponse = this.parseJsonRecord(responseText);
        this.submitMessage = this.resolveSubmitMessage(endpointLabel, parsedResponse);
        this.responseText = responseText;
      },
      error: (queueError: HttpErrorResponse) => {
        this.isSubmitting = false;
        this.errorMessage = this.resolveError(queueError) || this.resolveError(originalError);
        this.responseText = this.resolveResponse(queueError) || this.resolveResponse(originalError);
      },
    });
  }

  private resolveSubmitMessage(endpointLabel: string, parsedResponse: Record<string, unknown> | null): string {
    if (parsedResponse && parsedResponse['queued'] === true) {
      const queueId = parsedResponse['queue_id'];
      const queueText = queueId !== undefined && queueId !== null ? ` Queue ID: ${queueId}.` : '';
      const offlineVoucherNumber = typeof parsedResponse['offline_voucher_number'] === 'string'
        ? parsedResponse['offline_voucher_number'].trim()
        : '';
      const voucherText = offlineVoucherNumber ? ` Offline voucher no: ${offlineVoucherNumber}.` : '';
      return `${endpointLabel} voucher saved offline successfully.${queueText}${voucherText} It is stored in the database now and will wait for your sync review popup when TallyPrime comes back online.`;
    }
    const importResult = parsedResponse && typeof parsedResponse['IMPORTRESULT'] === 'object'
      ? parsedResponse['IMPORTRESULT'] as Record<string, unknown>
      : null;
    const connectorWarning = parsedResponse && typeof parsedResponse['_connector_warning'] === 'object'
      ? parsedResponse['_connector_warning'] as Record<string, unknown>
      : null;
    if (importResult && this.hasSuccessfulImportCounts(importResult)) {
      if (connectorWarning) {
        return `${endpointLabel} request created successfully. Tally verification may lag for a moment.`;
      }
      return `${endpointLabel} request submitted successfully.`;
    }
    return `${endpointLabel} request submitted successfully.`;
  }

  private hasSuccessfulImportCounts(importResult: Record<string, unknown>): boolean {
    return ['CREATED', 'ALTERED', 'DELETED'].some((key) => {
      const value = Number(importResult[key] || 0);
      return Number.isFinite(value) && value > 0;
    });
  }

  private escapeXml(value: string): string {
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&apos;');
  }

  private toArray<T>(value: T | T[] | null | undefined): T[] {
    if (Array.isArray(value)) {
      return value;
    }
    return value ? [value] : [];
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

  private asText(value: unknown): string {
    if (typeof value === 'string') {
      return value.trim();
    }
    if (typeof value === 'number') {
      return String(value);
    }
    if (value && typeof value === 'object') {
      const record = value as Record<string, unknown>;
      const textValue = record['#text'];
      if (typeof textValue === 'string') {
        return textValue.trim();
      }
      if (typeof textValue === 'number') {
        return String(textValue);
      }
      const nestedName = record['NAME'];
      if (typeof nestedName === 'string') {
        return nestedName.trim();
      }
    }
    return '';
  }

  private buildUiSectionsFromText(responseText: string, fallbackTitle: string): UiSection[] {
    if (!responseText.trim()) {
      return [];
    }

    try {
      const parsed = JSON.parse(responseText);
      return this.buildUiSectionsFromValue(parsed, fallbackTitle);
    } catch {
      return [
        {
          title: fallbackTitle,
          fields: [{ label: 'Message', value: responseText.trim() }],
        },
      ];
    }
  }

  private buildUiSectionsFromValue(value: unknown, fallbackTitle: string): UiSection[] {
    if (Array.isArray(value)) {
      return value.map((entry, index) => ({
        title: `${fallbackTitle} ${index + 1}`,
        fields: this.flattenValue(entry),
      }));
    }

    if (!value || typeof value !== 'object') {
      return [
        {
          title: fallbackTitle,
          fields: [{ label: 'Value', value: this.stringifyValue(value) }],
        },
      ];
    }

    const record = value as Record<string, unknown>;
    const primitiveFields: UiField[] = [];
    const sections: UiSection[] = [];

    Object.entries(record).forEach(([key, entryValue]) => {
      if (this.isPrimitiveLike(entryValue)) {
        primitiveFields.push({
          label: this.formatLabel(key),
          value: this.stringifyValue(entryValue),
        });
        return;
      }

      if (Array.isArray(entryValue)) {
        const arrayFields = entryValue.reduce<UiField[]>((accumulator, item, index) => {
          return accumulator.concat(this.flattenValue(item, `${this.formatLabel(key)} ${index + 1}`));
        }, []);
        if (arrayFields.length) {
          sections.push({
            title: this.formatLabel(key),
            fields: arrayFields,
          });
        }
        return;
      }

      sections.push({
        title: this.formatLabel(key),
        fields: this.flattenValue(entryValue),
      });
    });

    if (primitiveFields.length) {
      sections.unshift({
        title: fallbackTitle,
        fields: primitiveFields,
      });
    }

    return sections;
  }

  private flattenValue(value: unknown, prefix = ''): UiField[] {
    if (this.isPrimitiveLike(value)) {
      return [{ label: prefix || 'Value', value: this.stringifyValue(value) }];
    }

    if (Array.isArray(value)) {
      return value.reduce<UiField[]>((accumulator, item, index) => {
        return accumulator.concat(this.flattenValue(item, prefix ? `${prefix} ${index + 1}` : `Item ${index + 1}`));
      }, []);
    }

    if (!value || typeof value !== 'object') {
      return [];
    }

    return Object.entries(value as Record<string, unknown>).reduce<UiField[]>((accumulator, entry) => {
      const key = entry[0];
      const entryValue = entry[1];
      return accumulator.concat(
        this.flattenValue(entryValue, prefix ? `${prefix} · ${this.formatLabel(key)}` : this.formatLabel(key))
      );
    }, []);
  }

  private isPrimitiveLike(value: unknown): boolean {
    return value === null || value === undefined || ['string', 'number', 'boolean'].includes(typeof value);
  }

  private stringifyValue(value: unknown): string {
    if (value === null || value === undefined || value === '') {
      return '—';
    }
    return String(value);
  }

  private formatLabel(key: string): string {
    return key
      .replace(/[_-]+/g, ' ')
      .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
      .replace(/\s+/g, ' ')
      .trim()
      .replace(/\b\w/g, (char) => char.toUpperCase());
  }

  private buildResponseMetrics(sections: UiSection[], responseText: string): UiMetric[] {
    const voucherNumber = this.findFieldValue(sections, ['Voucher Number', 'Voucher Identity · Voucher Number', 'Voucher Voucher Number']);
    const reference = this.findFieldValue(sections, ['Reference', 'Voucher Identity · Reference', 'Voucher Reference']);
    const irn = this.findFieldValue(sections, ['Irn', 'Tally Status · Irn']);
    const ewayBill = this.findFieldValue(sections, ['Tempgstewaybillnumber', 'Tally Status · Tempgstewaybillnumber', 'Ewb No']);
    const parsedResponse = this.parseJsonRecord(responseText);
    const importResult = parsedResponse && typeof parsedResponse['IMPORTRESULT'] === 'object'
      ? parsedResponse['IMPORTRESULT'] as Record<string, unknown>
      : null;
    const isQueued = parsedResponse?.['queued'] === true;
    const isProcessed = !!importResult && this.hasSuccessfulImportCounts(importResult);
    const needsAttention = !isProcessed && !isQueued && /error|failed/i.test(responseText);
    const resultValue = isQueued ? 'Queued' : isProcessed ? 'Processed' : responseText.trim() ? 'Waiting review' : 'Waiting';
    const resultTone: UiMetric['tone'] = needsAttention ? 'accent' : (isQueued || isProcessed) ? 'success' : 'default';

    return [
      { label: 'Voucher No', value: voucherNumber || this.form.controls.invoiceNumber.value || 'Pending', tone: 'accent' },
      { label: 'Reference', value: reference || this.form.controls.invoiceNumber.value || 'Pending' },
      { label: 'IRN', value: irn || (this.isEinvoiceComplianceEndpoint ? 'Pending' : 'Not required'), tone: irn && irn !== '—' ? 'success' : 'default' },
      { label: 'E-Way Bill', value: ewayBill || (this.isEwayComplianceEndpoint ? 'Pending' : 'Not required'), tone: ewayBill && ewayBill !== '—' ? 'success' : 'default' },
      {
        label: 'Result',
        value: needsAttention ? 'Needs attention' : resultValue,
        tone: resultTone,
      },
    ];
  }

  private buildStatusMetrics(sections: UiSection[], responseText: string): UiMetric[] {
    const irn = this.findFieldValue(sections, ['Irn', 'Voucher 1 · Irn', 'Voucher · Irn']);
    const ewayBill = this.findFieldValue(sections, ['Tempgstewaybillnumber', 'Voucher 1 · Tempgstewaybillnumber', 'Ewb No']);
    const ackNo = this.findFieldValue(sections, ['Irnackno', 'Voucher 1 · Irnackno']);
    const status = this.findFieldValue(sections, ['Tempgstewaystatus', 'Status', 'Voucher 1 · Tempgstewaystatus']);

    return [
      { label: 'IRN', value: irn || (this.selectedStatusEndpoint.key.includes('einvoice') ? 'Pending' : 'Not required'), tone: irn && irn !== '—' ? 'success' : 'default' },
      { label: 'Ack No', value: ackNo || 'Pending', tone: ackNo && ackNo !== '—' ? 'success' : 'default' },
      { label: 'E-Way Bill', value: ewayBill || (this.selectedStatusEndpoint.key.includes('ewaybill') ? 'Pending' : 'Not required'), tone: ewayBill && ewayBill !== '—' ? 'success' : 'default' },
      { label: 'Status', value: status || (responseText.trim() ? 'Returned' : 'Waiting'), tone: status && status !== '—' ? 'success' : 'default' },
    ];
  }

  private findFieldValue(sections: UiSection[], labels: string[]): string {
    const normalizedLabels = labels.map((label) => label.toLowerCase());
    for (const section of sections) {
      for (const field of section.fields) {
        if (normalizedLabels.includes(field.label.toLowerCase())) {
          return field.value;
        }
      }
    }
    return '';
  }
}
