import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface VoucherEndpointOption {
  key: string;
  label: string;
  path: string;
  description: string;
  contentType: 'application/json' | 'application/xml';
  requestKind: 'voucher' | 'compliance' | 'xml';
  defaultVoucherType: string;
  createStatus?: 'working' | 'unsupported' | 'experimental';
}

export interface VoucherCreateRequest {
  path: string;
  action?: string;
  contentType: 'application/json' | 'application/xml';
  payload: unknown;
  company?: string;
}

export interface VoucherComplianceStatusRequest {
  path: string;
  voucherNumber?: string;
  reference?: string;
  partyLedgerName?: string;
  fromDate?: string;
  toDate?: string;
  limit?: number | string;
  company?: string;
}

export interface OfflineVoucherStoreRequest {
  path: string;
  action?: string;
  company?: string;
  contentType: 'application/json' | 'application/xml';
  requestBody: string;
  reason?: string;
}

export interface VoucherQueueStatusRequest {
  limit?: number | string;
  company?: string;
  connectorPath?: string;
  status?: string;
}

export const VOUCHER_ENDPOINTS: VoucherEndpointOption[] = [
  { key: 'vouchers', label: 'Vouchers', path: '/api/vouchers', description: 'Generic voucher upsert route.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Journal', createStatus: 'unsupported' },
  { key: 'sales', label: 'Sales', path: '/api/vouchers/sales', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Sales', createStatus: 'working' },
  { key: 'purchase', label: 'Purchase', path: '/api/vouchers/purchase', description: 'Working with inventory-style invoice payloads in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Purchase', createStatus: 'working' },
  { key: 'sales-orders', label: 'Sales Orders', path: '/api/vouchers/sales-orders', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Sales Order', createStatus: 'working' },
  { key: 'purchase-orders', label: 'Purchase Orders', path: '/api/vouchers/purchase-orders', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Purchase Order', createStatus: 'working' },
  { key: 'delivery-notes', label: 'Delivery Notes', path: '/api/vouchers/delivery-notes', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Delivery Note', createStatus: 'working' },
  { key: 'goods-receipts', label: 'Goods Receipts', path: '/api/vouchers/goods-receipts', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Receipt Note', createStatus: 'working' },
  { key: 'stock-journals', label: 'Stock Journals', path: '/api/vouchers/stock-journals', description: 'Currently unsupported for create here.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Stock Journal', createStatus: 'unsupported' },
  { key: 'material-in', label: 'Material In', path: '/api/vouchers/material-in', description: 'Currently unsupported for create here.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Material In', createStatus: 'unsupported' },
  { key: 'material-out', label: 'Material Out', path: '/api/vouchers/material-out', description: 'Currently unsupported for create here.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Material Out', createStatus: 'unsupported' },
  { key: 'manufacturing', label: 'Manufacturing', path: '/api/vouchers/manufacturing', description: 'Needs stock-journal style inventory data or voucher type setup.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Stock Journal', createStatus: 'experimental' },
  { key: 'receipt', label: 'Receipt', path: '/api/vouchers/receipt', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Receipt', createStatus: 'working' },
  { key: 'payment', label: 'Payment', path: '/api/vouchers/payment', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Payment', createStatus: 'working' },
  { key: 'contra', label: 'Contra', path: '/api/vouchers/contra', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Contra', createStatus: 'working' },
  { key: 'journal', label: 'Journal', path: '/api/vouchers/journal', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Journal', createStatus: 'working' },
  { key: 'credit-notes', label: 'Credit Notes', path: '/api/vouchers/credit-notes', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Credit Note', createStatus: 'working' },
  { key: 'debit-notes', label: 'Debit Notes', path: '/api/vouchers/debit-notes', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Debit Note', createStatus: 'working' },
  { key: 'rejections-in', label: 'Rejections In', path: '/api/vouchers/rejections-in', description: 'Currently unsupported for create here.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Rejections In', createStatus: 'unsupported' },
  { key: 'rejections-out', label: 'Rejections Out', path: '/api/vouchers/rejections-out', description: 'Currently unsupported for create here.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Rejections Out', createStatus: 'unsupported' },
  { key: 'payroll', label: 'Payroll', path: '/api/vouchers/payroll', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Payroll', createStatus: 'working' },
  { key: 'physical-stock', label: 'Physical Stock', path: '/api/vouchers/physical-stock', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Physical Stock', createStatus: 'working' },
  { key: 'attendance', label: 'Attendance', path: '/api/vouchers/attendance', description: 'Currently unsupported for create here.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Attendance', createStatus: 'unsupported' },
  { key: 'job-work-in-orders', label: 'Job Work In Orders', path: '/api/vouchers/job-work-in-orders', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Job Work In Order', createStatus: 'working' },
  { key: 'job-work-out-orders', label: 'Job Work Out Orders', path: '/api/vouchers/job-work-out-orders', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Job Work Out Order', createStatus: 'working' },
  { key: 'memorandum', label: 'Memorandum', path: '/api/vouchers/memorandum', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Memorandum', createStatus: 'working' },
  { key: 'reversing-journal', label: 'Reversing Journal', path: '/api/vouchers/reversing-journal', description: 'Working in this local connector.', contentType: 'application/json', requestKind: 'voucher', defaultVoucherType: 'Reversing Journal', createStatus: 'working' },
  { key: 'import-xml', label: 'Import XML', path: '/api/vouchers/import-xml', description: 'Send a generated voucher XML request.', contentType: 'application/xml', requestKind: 'xml', defaultVoucherType: 'Journal' },
  { key: 'einvoice-generate', label: 'E-Invoice Generate', path: '/api/vouchers/einvoice/generate', description: 'Trigger generic e-invoice generation.', contentType: 'application/json', requestKind: 'compliance', defaultVoucherType: 'Sales' },
  { key: 'sales-einvoice-generate', label: 'Sales E-Invoice Generate', path: '/api/vouchers/sales/einvoice/generate', description: 'Trigger sales e-invoice generation.', contentType: 'application/json', requestKind: 'compliance', defaultVoucherType: 'Sales' },
  { key: 'ewaybill-generate', label: 'E-Way Bill Generate', path: '/api/vouchers/ewaybill/generate', description: 'Trigger generic e-way bill generation.', contentType: 'application/json', requestKind: 'compliance', defaultVoucherType: 'Sales' },
  { key: 'sales-ewaybill-generate', label: 'Sales E-Way Bill Generate', path: '/api/vouchers/sales/ewaybill/generate', description: 'Trigger sales e-way bill generation.', contentType: 'application/json', requestKind: 'compliance', defaultVoucherType: 'Sales' },
  { key: 'einvoice-ewaybill-generate', label: 'E-Invoice + E-Way Bill Generate', path: '/api/vouchers/einvoice-ewaybill/generate', description: 'Trigger combined generation.', contentType: 'application/json', requestKind: 'compliance', defaultVoucherType: 'Sales' },
  { key: 'sales-einvoice-ewaybill-generate', label: 'Sales E-Invoice + E-Way Bill Generate', path: '/api/vouchers/sales/einvoice-ewaybill/generate', description: 'Trigger combined sales generation.', contentType: 'application/json', requestKind: 'compliance', defaultVoucherType: 'Sales' },
  { key: 'einvoice-ready', label: 'E-Invoice Ready', path: '/api/vouchers/einvoice-ready', description: 'Mark generic voucher ready for e-invoice.', contentType: 'application/json', requestKind: 'compliance', defaultVoucherType: 'Sales' },
  { key: 'sales-einvoice-ready', label: 'Sales E-Invoice Ready', path: '/api/vouchers/sales/einvoice-ready', description: 'Mark sales voucher ready for e-invoice.', contentType: 'application/json', requestKind: 'compliance', defaultVoucherType: 'Sales' },
  { key: 'ewaybill-ready', label: 'E-Way Bill Ready', path: '/api/vouchers/ewaybill-ready', description: 'Mark generic voucher ready for e-way bill.', contentType: 'application/json', requestKind: 'compliance', defaultVoucherType: 'Sales' },
  { key: 'sales-ewaybill-ready', label: 'Sales E-Way Bill Ready', path: '/api/vouchers/sales/ewaybill-ready', description: 'Mark sales voucher ready for e-way bill.', contentType: 'application/json', requestKind: 'compliance', defaultVoucherType: 'Sales' },
  { key: 'einvoice-ewaybill-ready', label: 'E-Invoice + E-Way Bill Ready', path: '/api/vouchers/einvoice-ewaybill-ready', description: 'Mark generic voucher ready for combined processing.', contentType: 'application/json', requestKind: 'compliance', defaultVoucherType: 'Sales' },
  { key: 'sales-einvoice-ewaybill-ready', label: 'Sales E-Invoice + E-Way Bill Ready', path: '/api/vouchers/sales/einvoice-ewaybill-ready', description: 'Mark sales voucher ready for combined processing.', contentType: 'application/json', requestKind: 'compliance', defaultVoucherType: 'Sales' },
];

@Injectable({
  providedIn: 'root',
})
export class VouchersApiService {
  constructor(private http: HttpClient) {}

  createVoucher(request: VoucherCreateRequest): Observable<any> {
    const headers = new HttpHeaders({
      'Content-Type': request.contentType,
    });

    let params = new HttpParams();
    if (request.action) {
      params = params.set('action', request.action);
    }
    if (request.company) {
      params = params.set('company', request.company);
    }

    return this.http.post(request.path, request.payload, {
      headers,
      params: params.keys().length ? params : undefined,
      responseType: 'text',
    });
  }

  storeVoucherOffline(request: OfflineVoucherStoreRequest): Observable<any> {
    return this.http.post('/api/voucher-queue/store', request, {
      responseType: 'text',
    });
  }

  getSalesVouchers(company: string, fromDate: string, toDate: string, limit: number | string = 100): Observable<any> {
    let params = new HttpParams()
      .set('from_date', fromDate)
      .set('to_date', toDate)
      .set('view', 'summary')
      .set('limit', `${limit}`);
    if (company) {
      params = params.set('company', company);
    }
    return this.http.get('/api/vouchers/sales', { params });
  }

  getVoucherQueue(request: VoucherQueueStatusRequest): Observable<any> {
    let params = new HttpParams();
    if (request.limit !== undefined && request.limit !== null && `${request.limit}`.trim()) {
      params = params.set('limit', `${request.limit}`);
    }
    if (request.company) {
      params = params.set('company', request.company);
    }
    if (request.connectorPath) {
      params = params.set('connectorPath', request.connectorPath);
    }
    if (request.status) {
      params = params.set('status', request.status);
    }
    return this.http.get('/api/voucher-queue', {
      params: params.keys().length ? params : undefined,
    });
  }

  getComplianceStatus(request: VoucherComplianceStatusRequest): Observable<any> {
    let params = new HttpParams();

    if (request.voucherNumber) {
      params = params.set('voucher_number', request.voucherNumber);
    }
    if (request.reference) {
      params = params.set('reference', request.reference);
    }
    if (request.partyLedgerName) {
      params = params.set('party_ledger_name', request.partyLedgerName);
    }
    if (request.fromDate) {
      params = params.set('from_date', request.fromDate);
    }
    if (request.toDate) {
      params = params.set('to_date', request.toDate);
    }
    if (request.limit !== undefined && request.limit !== null && `${request.limit}`.trim()) {
      params = params.set('limit', `${request.limit}`);
    }
    if (request.company) {
      params = params.set('company', request.company);
    }

    return this.http.get(request.path, {
      params,
      responseType: 'text',
    });
  }
}
