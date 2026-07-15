import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, OnDestroy } from '@angular/core';
import { BehaviorSubject, Subject, Subscription, combineLatest, interval, merge, of } from 'rxjs';
import { catchError, map, startWith, switchMap } from 'rxjs/operators';

import { CompanyContextService } from './company-context.service';

interface QueueEntry {
  company?: string | null;
  status?: string | null;
  completedAt?: string | null;
}

export interface SyncMonitorState {
  online: boolean;
  statusLabel: string;
  message: string;
  checkedAt: string;
  pendingQueueCount: number;
  failedQueueCount: number;
  retryQueueCount: number;
  recentAppliedAt: string;
  syncIntervalMs: number;
  retryIntervalMs: number;
  reviewRequired: boolean;
  transitionedOnline: boolean;
  pendingReviewCount: number;
  conflictCount: number;
  reconciliationSignature: string;
}

@Injectable({
  providedIn: 'root',
})
export class SyncMonitorService implements OnDestroy {
  private readonly stateSubject = new BehaviorSubject<SyncMonitorState>({
    online: false,
    statusLabel: 'Checking Status',
    message: 'Checking Tally status...',
    checkedAt: '',
    pendingQueueCount: 0,
    failedQueueCount: 0,
    retryQueueCount: 0,
    recentAppliedAt: '',
    syncIntervalMs: 10000,
    retryIntervalMs: 10000,
    reviewRequired: false,
    transitionedOnline: false,
    pendingReviewCount: 0,
    conflictCount: 0,
    reconciliationSignature: '',
  });
  private readonly refreshSubject = new Subject<void>();
  private readonly subscriptions = new Subscription();
  private previousPendingQueueCount = 0;
  private previousRecentAppliedAt = '';

  readonly state$ = this.stateSubject.asObservable();
  readonly refreshRequested$ = this.refreshSubject.asObservable();

  constructor(
    private http: HttpClient,
    private companyContextService: CompanyContextService
  ) {
    this.subscriptions.add(
      combineLatest([
        this.companyContextService.selectedCompany$,
        merge(interval(10000), this.refreshSubject).pipe(startWith(0)),
      ])
        .pipe(
          switchMap(([company]) => this.pollState(company || ''))
        )
        .subscribe((state: SyncMonitorState) => {
          const shouldRefresh = (this.previousPendingQueueCount > 0 && state.pendingQueueCount === 0)
            || (this.previousRecentAppliedAt !== '' && state.recentAppliedAt !== '' && this.previousRecentAppliedAt !== state.recentAppliedAt);

          this.previousPendingQueueCount = state.pendingQueueCount;
          this.previousRecentAppliedAt = state.recentAppliedAt;
          this.stateSubject.next(state);

          if (shouldRefresh) {
            this.refreshSubject.next();
          }
        })
    );
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  private pollState(company: string) {
    const queueParams = company ? new HttpParams().set('limit', '100').set('company', company) : new HttpParams().set('limit', '100');
    return combineLatest([
      this.http.get('/api/cache/status', {
        params: company ? new HttpParams().set('company', company) : undefined,
      }).pipe(catchError(() => of({}))),
      this.http.get('/api/voucher-queue', {
        params: queueParams,
      }).pipe(catchError(() => of({ entries: [] }))),
      this.http.get('/api/reconciliation/status', {
        params: company ? new HttpParams().set('company', company) : undefined,
      }).pipe(catchError(() => of({}))),
    ]).pipe(
      map(([statusResponse, queueResponse, reconciliationResponse]) => {
        const connectorHealth = (statusResponse as any)?.connector_health || {};
        const queueEntries = this.filterCompanyEntries((queueResponse as any)?.entries, company);
        const pendingQueueCount = queueEntries.filter((entry) => this.isPending(entry?.status)).length;
        const failedQueueCount = queueEntries.filter((entry) => this.asText(entry?.status).toUpperCase() === 'FAILED').length;
        const retryQueueCount = queueEntries.filter((entry) => this.asText(entry?.status).toUpperCase() === 'RETRY').length;
        const recentAppliedAt = queueEntries
          .map((entry) => entry?.completedAt || '')
          .filter((value) => !!value)
          .sort()
          .reverse()[0] || '';
        const tallyReachable = this.readBoolean(connectorHealth.tally_upstream_reachable);
        const connectorReachable = this.readBoolean(connectorHealth.reachable);
        const connectorHealthy = this.asText(connectorHealth.status).toLowerCase() === 'ok';
        const effectiveOnline = tallyReachable ?? ((connectorReachable ?? false) && connectorHealthy);
        const connectorMessage = this.asText(connectorHealth.message);
        const connectorUnreachable = connectorReachable === false
          || connectorMessage.toLowerCase().includes('127.0.0.1:8082')
          || connectorMessage.toLowerCase().includes('connector');
        const effectiveMessage = tallyReachable === false
          ? 'Tally listener on 127.0.0.1:9000 is not reachable. Open TallyPrime and enable the XML/HTTP listener.'
          : connectorMessage || 'Tally status unavailable.';
        const statusLabel = effectiveOnline
          ? 'Tally Online'
          : connectorUnreachable && tallyReachable !== false
            ? 'Connector Offline'
            : 'Tally Offline';

        return {
          online: effectiveOnline,
          statusLabel,
          message: effectiveMessage,
          checkedAt: this.asText(connectorHealth.checked_at),
          pendingQueueCount,
          failedQueueCount,
          retryQueueCount,
          recentAppliedAt,
          syncIntervalMs: Number((statusResponse as any)?.sync_interval_ms) || 10000,
          retryIntervalMs: Number((queueResponse as any)?.retry_interval_ms) || Number((statusResponse as any)?.sync_interval_ms) || 10000,
          reviewRequired: (reconciliationResponse as any)?.review_required === true,
          transitionedOnline: (reconciliationResponse as any)?.transitioned_online === true,
          pendingReviewCount: Number((reconciliationResponse as any)?.pending_review_count) || 0,
          conflictCount: Number((reconciliationResponse as any)?.conflict_count) || 0,
          reconciliationSignature: this.asText((reconciliationResponse as any)?.signature),
        } as SyncMonitorState;
      })
    );
  }

  cleanupFailed(company: string) {
    const params = company
      ? new HttpParams().set('company', company).set('status', 'FAILED')
      : new HttpParams().set('status', 'FAILED');
    return this.http.post<{ deleted?: number }>('/api/voucher-queue/cleanup', null, { params });
  }

  cleanupRetry(connectorBaseUrl?: string, olderThanMinutes?: number) {
    let params = new HttpParams().set('status', 'RETRY');
    if (connectorBaseUrl) {
      params = params.set('connectorBaseUrl', connectorBaseUrl);
    }
    if (typeof olderThanMinutes === 'number' && olderThanMinutes > 0) {
      params = params.set('olderThanMinutes', String(olderThanMinutes));
    }
    return this.http.post<{ deleted?: number }>('/api/voucher-queue/cleanup', null, { params });
  }

  cleanupHistory(company: string, olderThanMinutes?: number) {
    let params = company
      ? new HttpParams().set('company', company).set('status', 'HISTORY')
      : new HttpParams().set('status', 'HISTORY');
    if (typeof olderThanMinutes === 'number' && olderThanMinutes > 0) {
      params = params.set('olderThanMinutes', String(olderThanMinutes));
    }
    return this.http.post<{ deleted?: number }>('/api/voucher-queue/cleanup', null, { params });
  }

  refreshNow(): void {
    this.refreshSubject.next();
  }

  private filterCompanyEntries(entries: QueueEntry[] | null | undefined, company: string): QueueEntry[] {
    if (!Array.isArray(entries)) {
      return [];
    }
    if (!company) {
      return entries;
    }
    const normalizedCompany = company.trim().toLowerCase();
    return entries.filter((entry) => {
      const entryCompany = this.asText(entry?.company).toLowerCase();
      return entryCompany === normalizedCompany;
    });
  }

  private isPending(status: string | null | undefined): boolean {
    const normalized = this.asText(status).toUpperCase();
    return normalized === 'QUEUED' || normalized === 'RETRY' || normalized === 'PROCESSING';
  }

  private asText(value: unknown): string {
    return typeof value === 'string' ? value : value == null ? '' : String(value);
  }

  private readBoolean(value: unknown): boolean | null {
    if (typeof value === 'boolean') {
      return value;
    }
    if (typeof value === 'string') {
      const normalized = value.trim().toLowerCase();
      if (normalized === 'true') {
        return true;
      }
      if (normalized === 'false') {
        return false;
      }
    }
    return null;
  }
}
