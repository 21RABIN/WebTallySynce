import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { Subscription } from 'rxjs';

import { CompanyContextService } from '../../../core/api/company-context.service';
import { CacheExportApiService, ExportFormat } from '../../../core/api/cache-export-api.service';
import { MastersApiService, StockItemPayload } from '../../../core/api/masters-api.service';
import { SyncMonitorService } from '../../../core/api/sync-monitor.service';

interface StockItemRecord {
  name: string;
  parent: string;
  baseUnits: string;
  hsnCode: string;
  gstApplicable: string;
  quantity: string;
  rate: string;
  value: string;
}

@Component({
  selector: 'app-stock-items',
  templateUrl: './stock-items.component.html',
  styleUrls: ['./stock-items.component.scss'],
})
export class StockItemsComponent implements OnInit, OnDestroy {
  stockItems: StockItemRecord[] = [];
  isLoading = true;
  isSubmitting = false;
  parentOptions: string[] = [];
  uomOptions: string[] = [];
  errorMessage = '';
  submitMessage = '';
  responseMeta: any = null;
  selectedCompany = '';
  selectedStockItemName = '';
  createMode = false;
  editMode = false;
  searchTerm = '';
  private readonly subscriptions = new Subscription();

  readonly form = this.formBuilder.group({
    NAME: ['', Validators.required],
    PARENT: ['', Validators.required],
    BASEUNITS: ['', Validators.required],
    HSNCODE: [''],
    QUANTITY: [null as number | null, [Validators.required, Validators.min(0)]],
    RATEPER: [null as number | null, [Validators.required, Validators.min(0)]],
    GSTAPPLICABLE: ['Applicable'],
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
      this.loadUomOptions();
      this.load();
    }));
    this.subscriptions.add(this.syncMonitorService.refreshRequested$.subscribe(() => {
      this.loadParentOptions();
      this.loadUomOptions();
      this.load();
    }));
    this.loadParentOptions();
    this.loadUomOptions();
    this.load();
    this.companyContextService.refreshCompanies();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  get selectedStockItem(): StockItemRecord | null {
    return this.stockItems.find((item) => item.name === this.selectedStockItemName) || null;
  }

  get detailTitle(): string {
    return this.createMode ? 'Stock Item Creation' : 'Stock Item Alteration';
  }

  get isEditable(): boolean {
    return this.createMode || this.editMode;
  }

  get filteredStockItems(): StockItemRecord[] {
    const term = this.searchTerm.trim().toLowerCase();
    if (!term) {
      return this.stockItems;
    }
    return this.stockItems.filter((item) =>
      [item.name, item.parent, item.baseUnits, item.hsnCode, item.gstApplicable].some((value) => value.toLowerCase().includes(term))
    );
  }

  get gstApplicableCount(): number {
    return this.stockItems.filter((item) => (item.gstApplicable || '').toLowerCase() === 'applicable').length;
  }

  get unitMappedCount(): number {
    return this.stockItems.filter((item) => !!item.baseUnits).length;
  }

  get inventoryValueTotal(): string {
    const total = this.stockItems.reduce((sum, item) => sum + Math.abs(this.toNumber(item.value)), 0);
    return this.formatValue(total);
  }

  get trackedHsnCount(): number {
    return this.stockItems.filter((item) => !!(item.hsnCode || '').trim()).length;
  }

  get selectedInsight(): string {
    if (this.createMode) {
      return 'New inventory item draft';
    }
    return this.selectedStockItem?.name || 'No stock item selected';
  }

  get quantityFieldLabel(): string {
    return this.createMode ? 'Opening Quantity' : 'Current Quantity';
  }

  get valueSummaryLabel(): string {
    return this.createMode ? 'Opening Value' : 'Current Stock Value';
  }

  get selectedQuantityDisplay(): string {
    if (this.createMode) {
      return this.trimNumber(this.toNonNegativeNumber(this.form.get('QUANTITY')?.value));
    }
    return this.selectedStockItem?.quantity || '0';
  }

  get selectedRateDisplay(): string {
    if (this.createMode) {
      return this.trimNumber(this.toNonNegativeNumber(this.form.get('RATEPER')?.value));
    }
    return this.selectedStockItem?.rate || '0';
  }

  get selectedValueDisplay(): string {
    if (this.createMode) {
      return this.formatValue(this.openingValue);
    }
    return this.selectedStockItem?.value || '0.00';
  }

  download(format: ExportFormat): void {
    this.cacheExportApiService.downloadAndSave('masters', 'stock-items', format, {
      company: this.selectedCompany || '',
    });
  }

  load(): void {
    this.isLoading = true;
    this.errorMessage = '';
    this.mastersApiService.getStockItems(this.selectedCompany).subscribe({
      next: (response) => {
        this.responseMeta = response?.meta || null;
        const rawStockItems = this.toArray(response?.data || response?.STOCKITEM);
        this.stockItems = rawStockItems
          .map((item) => {
            const parsedNumbers = this.parseStockItemNumbers(item);
            return {
              name: this.readText(item, 'NAME', 'name'),
              parent: this.readText(item, 'PARENT', 'parent'),
              baseUnits: this.readText(item, 'BASEUNITS', 'base_units'),
              hsnCode: this.readText(item, 'HSNCODE', 'hsn_code'),
              gstApplicable: this.readText(item, 'GSTAPPLICABLE', 'gst_applicable'),
              quantity: parsedNumbers.quantity,
              rate: parsedNumbers.rate,
              value: parsedNumbers.value,
            };
          })
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
    if (this.form.invalid || this.isSubmitting || (!this.createMode && !this.editMode)) {
      this.form.markAllAsTouched();
      return;
    }

    this.isSubmitting = true;
    this.submitMessage = '';
    const formValue = this.form.getRawValue();
    const quantity = this.toNonNegativeNumber(formValue.QUANTITY);
    const ratePer = this.toNonNegativeNumber(formValue.RATEPER);
    const openingValue = quantity * ratePer;
    const payload: StockItemPayload = {
      NAME: formValue.NAME || '',
      PARENT: formValue.PARENT || '',
      BASEUNITS: formValue.BASEUNITS || '',
      HSNCODE: formValue.HSNCODE || '',
      GSTAPPLICABLE: formValue.GSTAPPLICABLE || 'Applicable',
      QUANTITY: quantity,
      RATEPER: ratePer,
      OPENINGVALUE: this.formatValue(openingValue),
      OPENINGBALANCE: this.buildOpeningBalance(quantity, formValue.BASEUNITS || ''),
      OPENINGRATE: this.buildOpeningRate(ratePer, formValue.BASEUNITS || ''),
    };

    const request$ = this.createMode
      ? this.mastersApiService.createStockItem(payload, this.selectedCompany)
      : this.mastersApiService.updateStockItem(payload, this.selectedCompany);

    request$.subscribe({
      next: (response) => {
        this.isSubmitting = false;
        this.submitMessage = this.resolveSubmitMessage(payload.NAME, response, this.createMode ? 'create' : 'alter');
        this.upsertLocalStockItem(payload);
        this.selectStockItem(payload.NAME);
        this.loadParentOptions();
        this.loadUomOptions();
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
    this.mastersApiService.getStockGroups(this.selectedCompany).subscribe({
      next: (response) => {
        const rawStockGroups = this.toArray(response?.data || response?.STOCKGROUP);
        this.parentOptions = rawStockGroups
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

  private loadUomOptions(): void {
    this.mastersApiService.getUoms(this.selectedCompany).subscribe({
      next: (response) => {
        const rawUnits = this.toArray(response?.data || response?.UNIT);
        const values: string[] = [];
        rawUnits.forEach((item) => {
          this.readNames(this.readValue(item, 'NAME', 'name')).forEach((name) => values.push(name));
        });
        this.uomOptions = Array.from(new Set(values.filter((name) => !!name))).sort((left, right) => left.localeCompare(right));
        const defaultUom = this.resolveDefaultUom();
        if (defaultUom) {
          this.form.patchValue({ BASEUNITS: defaultUom });
        }
      },
      error: () => {
        this.uomOptions = [];
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
      const nestedName = record['NAME'];
      if (typeof nestedName === 'string') {
        return nestedName;
      }
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

  private readNames(value: unknown): string[] {
    if (typeof value === 'string') {
      return [value];
    }
    if (value && typeof value === 'object') {
      const record = value as Record<string, unknown>;
      const textValue = record['#text'];
      if (typeof textValue === 'string') {
        return [textValue];
      }
    }
    if (Array.isArray(value)) {
      return value.filter((item): item is string => typeof item === 'string');
    }
    return [];
  }

  private resolveDefaultParent(): string {
    if (this.parentOptions.includes('Primary')) {
      return 'Primary';
    }
    return this.parentOptions[0] || '';
  }

  private resolveDefaultUom(): string {
    if (this.uomOptions.includes('PCS')) {
      return 'PCS';
    }
    if (this.uomOptions.includes('PC')) {
      return 'PC';
    }
    return this.uomOptions[0] || '';
  }

  get openingValue(): number {
    return this.toNonNegativeNumber(this.form.get('QUANTITY')?.value) * this.toNonNegativeNumber(this.form.get('RATEPER')?.value);
  }

  formatValue(value: number): string {
    return value.toFixed(2);
  }

  private buildOpeningBalance(quantity: number, baseUnits: string): string {
    const unit = (baseUnits || '').trim();
    return unit ? `${this.trimNumber(quantity)} ${unit}` : this.trimNumber(quantity);
  }

  private buildOpeningRate(ratePer: number, baseUnits: string): string {
    const unit = (baseUnits || '').trim();
    return unit ? `${this.trimNumber(ratePer)}/${unit}` : this.trimNumber(ratePer);
  }

  private trimNumber(value: number): string {
    return value.toFixed(3).replace(/\.?0+$/, '');
  }

  private toNonNegativeNumber(value: unknown): number {
    const parsed = Number(value);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : 0;
  }

  private resolveError(error: HttpErrorResponse): string {
    if (error.error && typeof error.error.detail === 'string') {
      return error.error.detail;
    }
    if (error.error?.detail?.reason) {
      return String(error.error.detail.reason);
    }
    return 'Unable to load or save stock items from the backend.';
  }

  private resolveSubmitMessage(itemName: string, response: any, mode: 'create' | 'alter'): string {
    if (response && typeof response === 'object' && response.queued === true) {
      const queueId = response.queue_id !== undefined && response.queue_id !== null ? ` Queue ID: ${response.queue_id}.` : '';
      return `Stock item "${itemName}" was saved in the backend database and is waiting for sync approval.${queueId}`;
    }
    return mode === 'create'
      ? `Stock item "${itemName}" created successfully.`
      : `Stock item "${itemName}" updated successfully.`;
  }

  private parseOpeningBalance(openingBalance: string): { quantity: string; rate: string; value: string } {
    const text = (openingBalance || '').trim();
    if (!text) {
      return { quantity: '', rate: '', value: '' };
    }

    const quantityPart = text.includes('@') ? text.split('@')[0].trim() : text;
    const valueMatch = text.match(/=\s*([0-9]+(?:\.[0-9]+)?)/);
    const rateMatch = text.match(/@\s*([0-9]+(?:\.[0-9]+)?)/);

    return {
      quantity: quantityPart || '',
      rate: rateMatch ? rateMatch[1] : '',
      value: valueMatch ? valueMatch[1] : '',
    };
  }

  private parseStockItemNumbers(item: any): { quantity: string; rate: string; value: string } {
    if (
      item &&
      typeof item === 'object' &&
      (
        item.QUANTITY !== undefined ||
        item.RATEPER !== undefined ||
        item.OPENINGVALUE !== undefined ||
        item.quantity !== undefined ||
        item.rate !== undefined ||
        item.item_value !== undefined ||
        item.value !== undefined
      )
    ) {
      return {
        quantity: this.asText(item.QUANTITY ?? item.quantity),
        rate: this.asText(item.RATEPER ?? item.rate),
        value: this.formatValue(Math.abs(this.toNumber(item.OPENINGVALUE ?? item.item_value ?? item.value))),
      };
    }
    const parsedOpening = this.parseOpeningBalance(this.asText(item?.OPENINGBALANCE));
    const quantityText = parsedOpening.quantity || this.asText(item?.OPENINGBALANCE);
    const openingValueText = this.asText(item?.OPENINGVALUE);
    const openingRateText = this.asText(item?.OPENINGRATE);
    const openingValue = Math.abs(this.toNumber(openingValueText || parsedOpening.value));
    const quantityNumber = this.extractQuantityNumber(quantityText);
    const rateFromPayload = this.extractRateNumber(parsedOpening.rate || openingRateText);
    const rate = rateFromPayload || (quantityNumber > 0 ? openingValue / quantityNumber : 0);

    return {
      quantity: quantityText || '',
      rate: Number.isFinite(rate) ? this.formatValue(rate) : '',
      value: Number.isFinite(openingValue) ? this.formatValue(openingValue) : '',
    };
  }

  private extractQuantityNumber(quantityText: string): number {
    const match = (quantityText || '').trim().match(/^([0-9]+(?:\.[0-9]+)?)/);
    if (!match) {
      return 0;
    }
    return this.toNumber(match[1]);
  }

  private extractRateNumber(rateText: string): number {
    const match = (rateText || '').trim().match(/-?[0-9]+(?:\.[0-9]+)?/);
    if (!match) {
      return 0;
    }
    return this.toNumber(match[0]);
  }

  private toNumber(value: unknown): number {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  private upsertLocalStockItem(payload: StockItemPayload): void {
    const nextStockItem: StockItemRecord = {
      name: this.asText(payload.NAME),
      parent: this.asText(payload.PARENT),
      baseUnits: this.asText(payload.BASEUNITS),
      gstApplicable: this.asText(payload.GSTAPPLICABLE),
      hsnCode: this.asText(payload.HSNCODE),
      quantity: this.asText(payload.QUANTITY),
      rate: this.asText(payload.RATEPER),
      value: this.asText(payload.OPENINGVALUE),
    };
    this.stockItems = [...this.stockItems.filter((item) => item.name.toLowerCase() !== nextStockItem.name.toLowerCase()), nextStockItem]
      .sort((left, right) => left.name.localeCompare(right.name));
    this.syncSelection(nextStockItem.name);
  }

  selectStockItem(name: string): void {
    this.createMode = false;
    this.editMode = true;
    this.selectedStockItemName = name;
    const selectedStockItem = this.stockItems.find((item) => item.name === name);
    this.form.reset({
      NAME: selectedStockItem?.name || '',
      PARENT: selectedStockItem?.parent || this.resolveDefaultParent(),
      BASEUNITS: selectedStockItem?.baseUnits || this.resolveDefaultUom(),
      HSNCODE: selectedStockItem?.hsnCode || '',
      QUANTITY: this.toFormNumber(selectedStockItem?.quantity),
      RATEPER: this.toFormNumber(selectedStockItem?.rate),
      GSTAPPLICABLE: selectedStockItem?.gstApplicable || 'Applicable',
    });
  }

  openCreateMode(): void {
    this.createMode = true;
    this.editMode = false;
    this.selectedStockItemName = '';
    this.submitMessage = '';
    this.errorMessage = '';
    this.form.reset({
      NAME: '',
      PARENT: this.resolveDefaultParent(),
      BASEUNITS: this.resolveDefaultUom(),
      HSNCODE: '',
      QUANTITY: null,
      RATEPER: null,
      GSTAPPLICABLE: 'Applicable',
    });
  }

  clearSearch(): void {
    this.searchTerm = '';
  }

  enableEditMode(): void {
    if (!this.selectedStockItem) {
      return;
    }
    this.createMode = false;
    this.editMode = true;
    this.submitMessage = '';
    this.errorMessage = '';
  }

  private syncSelection(preferredName?: string): void {
    if (this.createMode) {
      return;
    }

    const nextSelection =
      preferredName && this.stockItems.some((item) => item.name === preferredName)
        ? preferredName
        : this.stockItems.some((item) => item.name === this.selectedStockItemName)
          ? this.selectedStockItemName
          : this.stockItems[0]?.name || '';

    if (nextSelection) {
      this.selectStockItem(nextSelection);
      return;
    }

    this.selectedStockItemName = '';
    this.form.reset({
      NAME: '',
      PARENT: this.resolveDefaultParent(),
      BASEUNITS: this.resolveDefaultUom(),
      HSNCODE: '',
      QUANTITY: null,
      RATEPER: null,
      GSTAPPLICABLE: 'Applicable',
    });
  }

  private toFormNumber(value: string | undefined): number | null {
    const parsed = Number.parseFloat((value || '').trim());
    return Number.isFinite(parsed) ? parsed : null;
  }
}
