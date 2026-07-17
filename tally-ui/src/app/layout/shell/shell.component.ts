import { Component, OnDestroy } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { finalize } from 'rxjs/operators';
import { Subscription } from 'rxjs';
import { filter } from 'rxjs/operators';

import { CompanyContextService } from '../../core/api/company-context.service';
import { ReconciliationApiService } from '../../core/api/reconciliation-api.service';
import { SyncMonitorService } from '../../core/api/sync-monitor.service';
import { AuthService } from '../../core/auth/auth.service';

interface NavItem {
  label: string;
  route: string;
  badge?: string;
}

interface NavSection {
  label: string;
  items: NavItem[];
}

interface ReconciliationPreviewItem {
  queue_id: number;
  entity_type: string;
  entity_name: string;
  company?: string;
  status?: string;
  queue_status?: string;
  conflict_state?: string;
  review_state?: string;
  last_error?: string;
  attempts?: number;
  max_attempts?: number;
  waiting_for_approval?: boolean;
  blocked_by_replay_failure?: boolean;
  created_at?: string;
  updated_at?: string;
  last_attempt_at?: string;
  next_attempt_at?: string;
  completed_at?: string;
  preview_fields?: Record<string, unknown>;
  tally_preview_fields?: Record<string, unknown>;
  recommended_action?: string;
  default_selected_action?: string;
  bucket?: string;
  selectedAction?: string;
}

@Component({
  selector: 'app-shell',
  templateUrl: './shell.component.html',
  styleUrls: ['./shell.component.scss'],
})
export class ShellComponent implements OnDestroy {
  readonly currentUser$ = this.authService.currentUser$;
  readonly syncState$ = this.syncMonitorService.state$;
  readonly navSections: NavSection[] = [
    {
      label: 'Overview',
      items: [
        { label: 'Dashboard', route: '/dashboard', badge: 'Home' },
      ],
    },
    {
      label: 'Masters',
      items: [
        { label: 'Groups', route: '/masters/groups' },
        { label: 'Ledgers', route: '/masters/ledgers' },
        { label: 'Stock Groups', route: '/masters/stock-groups' },
        { label: 'Stock Items', route: '/masters/stock-items' },
        { label: 'Units', route: '/masters/uoms' },
        { label: 'Currencies', route: '/masters/currencies' },
      ],
    },
    {
      label: 'Reporting',
      items: [
        { label: 'Day Book', route: '/reports/day-book' },
        { label: 'Ledger Vouchers', route: '/reports/ledger-vouchers' },
        { label: 'Companies', route: '/reports/companies' },
        { label: 'Balance Sheet', route: '/reports/balance-sheet' },
        { label: 'Profit and Loss', route: '/reports/profit-loss' },
        { label: 'Report Explorer', route: '/reports/explorer', badge: 'API' },
      ],
    },
    {
      label: 'Operations',
      items: [
        { label: 'Voucher Studio', route: '/vouchers/create', badge: 'Create' },
        { label: 'Payment Reminders', route: '/operations/payment-reminders', badge: 'New' },
        { label: 'Business Config', route: '/operations/business-config', badge: 'API' },
        { label: 'Integration Studio', route: '/operations/integration-studio', badge: 'Sync' },
        { label: 'Company Currency', route: '/settings/company-currency' },
        { label: 'Company Features', route: '/settings/company-features' },
      ],
    },
  ];

  mobileNavOpen = false;
  sidebarCollapsed = false;
  companyOptions: string[] = [];
  selectedCompany = '';
  cleanupMessage = '';
  cleanupError = false;
  clearingFailed = false;
  clearingRetry = false;
  clearingHistory = false;
  reconciliationModalOpen = false;
  reconciliationLoading = false;
  reconciliationSyncing = false;
  reconciliationError = '';
  reconciliationSummary = '';
  reconciliationCounts: Record<string, number> = {};
  pendingReconciliationItems: ReconciliationPreviewItem[] = [];
  alreadyPresentItems: ReconciliationPreviewItem[] = [];
  conflictItems: ReconciliationPreviewItem[] = [];
  failedReplayItems: ReconciliationPreviewItem[] = [];
  recentTallyItems: Array<Record<string, unknown>> = [];
  expandedSections: Record<string, boolean> = {
    Overview: true,
    Masters: false,
    Reporting: false,
    Operations: false,
  };
  private currentReconciliationSignature = '';
  private closedReconciliationSignature = '';
  private readonly subscriptions = new Subscription();

  constructor(
    private authService: AuthService,
    private router: Router,
    private companyContextService: CompanyContextService,
    private syncMonitorService: SyncMonitorService,
    private reconciliationApiService: ReconciliationApiService
  ) {
    this.expandActiveSection();
    this.subscriptions.add(this.router.events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe(() => {
        this.mobileNavOpen = false;
        this.expandActiveSection();
      }));
    this.subscriptions.add(this.companyContextService.companies$.subscribe((companies) => {
      this.companyOptions = companies;
    }));
    this.subscriptions.add(this.companyContextService.selectedCompany$.subscribe((company) => {
      this.selectedCompany = company || '';
      this.reconciliationModalOpen = false;
      this.currentReconciliationSignature = '';
      this.closedReconciliationSignature = '';
    }));
    this.subscriptions.add(this.syncMonitorService.state$.subscribe((state) => {
      if (!state.online || !state.reviewRequired || !state.reconciliationSignature) {
        return;
      }
      if (this.reconciliationModalOpen) {
        return;
      }
      if (this.closedReconciliationSignature === state.reconciliationSignature) {
        return;
      }
      this.openReconciliationModal();
    }));
    this.companyContextService.refreshCompanies();
  }

  get currentRouteLabel(): string {
    for (const section of this.navSections) {
      const match = section.items.find((item) => this.router.isActive(item.route, {
        paths: 'exact',
        queryParams: 'ignored',
        fragment: 'ignored',
        matrixParams: 'ignored',
      }));
      if (match) {
        return match.label;
      }
    }
    return 'Workspace';
  }

  get displayedSelectedCompany(): string {
    const matchedCompany = this.companyOptions.find((company) => company.trim().toLowerCase() === this.selectedCompany.trim().toLowerCase());
    if (matchedCompany) {
      return matchedCompany;
    }
    return this.companyOptions[0] || '';
  }

  get displayedCompanyOptions(): string[] {
    return this.companyOptions;
  }

  toggleMobileNav(): void {
    this.mobileNavOpen = !this.mobileNavOpen;
  }

  toggleSidebarCollapse(): void {
    this.sidebarCollapsed = !this.sidebarCollapsed;
  }

  toggleSection(sectionLabel: string): void {
    this.expandedSections[sectionLabel] = !this.expandedSections[sectionLabel];
  }

  isSectionExpanded(sectionLabel: string): boolean {
    return !!this.expandedSections[sectionLabel];
  }

  isSectionActive(section: NavSection): boolean {
    return section.items.some((item) => this.router.isActive(item.route, {
      paths: 'exact',
      queryParams: 'ignored',
      fragment: 'ignored',
      matrixParams: 'ignored',
    }));
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }

  onCompanyChange(company: string): void {
    const nextCompany = (company || '').trim();
    if (!nextCompany || nextCompany === this.selectedCompany) {
      return;
    }
    const matchedCompany = this.companyOptions.find((item) => item.trim().toLowerCase() === nextCompany.toLowerCase());
    if (!matchedCompany) {
      return;
    }
    this.selectedCompany = matchedCompany;
    this.companyContextService.setSelectedCompany(matchedCompany);
    this.syncMonitorService.refreshNow();
  }

  clearFailedQueue(): void {
    if (this.clearingFailed) {
      return;
    }
    this.clearingFailed = true;
    this.cleanupMessage = '';
    this.syncMonitorService.cleanupFailed(this.selectedCompany)
      .pipe(finalize(() => {
        this.clearingFailed = false;
      }))
      .subscribe({
        next: (response) => {
          const deleted = Number(response?.deleted) || 0;
          const scope = this.selectedCompany ? ` for ${this.selectedCompany}` : '';
          this.cleanupError = false;
          this.cleanupMessage = deleted > 0
            ? `Cleared ${deleted} failed queue entr${deleted === 1 ? 'y' : 'ies'}${scope}.`
            : `No failed queue entries found${scope}.`;
          this.syncMonitorService.refreshNow();
        },
        error: () => {
          this.cleanupError = true;
          this.cleanupMessage = 'Could not clear failed queue entries right now.';
        },
      });
  }

  clearRetryQueue(): void {
    if (this.clearingRetry) {
      return;
    }
    this.clearingRetry = true;
    this.cleanupMessage = '';
    this.syncMonitorService.cleanupRetry('http://127.0.0.1:65530')
      .pipe(finalize(() => {
        this.clearingRetry = false;
      }))
      .subscribe({
        next: (response) => {
          const deleted = Number(response?.deleted) || 0;
          this.cleanupError = false;
          this.cleanupMessage = deleted > 0
            ? `Cleared ${deleted} stale retry entr${deleted === 1 ? 'y' : 'ies'}.`
            : 'No stale retry entries found.';
          this.syncMonitorService.refreshNow();
        },
        error: () => {
          this.cleanupError = true;
          this.cleanupMessage = 'Could not clear stale retry entries right now.';
        },
      });
  }

  clearHistoryQueue(): void {
    if (this.clearingHistory) {
      return;
    }
    this.clearingHistory = true;
    this.cleanupMessage = '';
    this.syncMonitorService.cleanupHistory(this.selectedCompany)
      .pipe(finalize(() => {
        this.clearingHistory = false;
      }))
      .subscribe({
        next: (response) => {
          const deleted = Number(response?.deleted) || 0;
          const scope = this.selectedCompany ? ` for ${this.selectedCompany}` : '';
          this.cleanupError = false;
          this.cleanupMessage = deleted > 0
            ? `Cleared ${deleted} old synced or skipped entr${deleted === 1 ? 'y' : 'ies'}${scope}.`
            : `No old synced or skipped entries found${scope}.`;
          this.syncMonitorService.refreshNow();
        },
        error: () => {
          this.cleanupError = true;
          this.cleanupMessage = 'Could not clear old synced or skipped history right now.';
        },
      });
  }

  openReconciliationModal(expectedSignature?: string): void {
    if (this.reconciliationLoading) {
      return;
    }
    this.reconciliationLoading = true;
    this.reconciliationError = '';
    this.reconciliationApiService.getPreview(this.selectedCompany)
      .pipe(finalize(() => {
        this.reconciliationLoading = false;
      }))
      .subscribe({
        next: (response) => {
          const signature = this.asText(response?.signature);
          if (expectedSignature && signature && signature !== expectedSignature) {
            this.syncMonitorService.refreshNow();
          }
          this.currentReconciliationSignature = signature;
          this.closedReconciliationSignature = '';
          this.reconciliationCounts = (response?.counts_by_entity_type || {}) as Record<string, number>;
          this.pendingReconciliationItems = this.withSelectedActions(response?.pending_db_to_tally);
          this.alreadyPresentItems = this.withSelectedActions(response?.already_present_in_tally);
          this.conflictItems = this.withSelectedActions(response?.possible_conflicts);
          this.failedReplayItems = this.withSelectedActions(response?.failed_replay_items);
          this.recentTallyItems = Array.isArray(response?.recent_tally_changes_visible_after_reconnect)
            ? response.recent_tally_changes_visible_after_reconnect
            : [];
          this.reconciliationSummary = `${this.pendingReconciliationItems.length} ready, ${this.conflictItems.length} conflicts, ${this.alreadyPresentItems.length} already present, ${this.failedReplayItems.length} failed replay.`;
          this.reconciliationModalOpen = true;
        },
        error: () => {
          this.reconciliationError = 'Could not load reconciliation preview right now.';
        },
      });
  }

  closeReconciliationModal(): void {
    this.reconciliationModalOpen = false;
    this.closedReconciliationSignature = this.currentReconciliationSignature;
  }

  dismissReconciliationModal(): void {
    this.reconciliationApiService.dismiss(this.selectedCompany).subscribe({
      next: () => {
        this.reconciliationModalOpen = false;
        this.currentReconciliationSignature = '';
        this.closedReconciliationSignature = '';
        this.syncMonitorService.refreshNow();
      },
      error: () => {
        this.reconciliationError = 'Could not dismiss the reconciliation preview right now.';
      },
    });
  }

  refreshReconciliationPreview(): void {
    this.openReconciliationModal();
  }

  syncApprovedReconciliationItems(): void {
    const items = [
      ...this.pendingReconciliationItems,
      ...this.failedReplayItems,
      ...this.alreadyPresentItems,
      ...this.conflictItems,
    ]
      .map((item) => ({
        queue_id: item.queue_id,
        action: item.selectedAction || item.default_selected_action || item.recommended_action || 'SKIP_FOR_NOW',
      }));

    if (!items.length) {
      this.reconciliationModalOpen = false;
      return;
    }

    this.reconciliationSyncing = true;
    this.reconciliationError = '';
    this.reconciliationApiService.sync(this.selectedCompany, items)
      .pipe(finalize(() => {
        this.reconciliationSyncing = false;
      }))
      .subscribe({
        next: (response) => {
          this.reconciliationSummary = `${Number(response?.synced) || 0} synced, ${Number(response?.marked_as_already_synced) || 0} marked as already synced, ${Number(response?.skipped) || 0} skipped.`;
          const preview = response?.preview || {};
          this.pendingReconciliationItems = this.withSelectedActions(preview?.pending_db_to_tally);
          this.failedReplayItems = this.withSelectedActions(preview?.failed_replay_items);
          this.alreadyPresentItems = this.withSelectedActions(preview?.already_present_in_tally);
          this.conflictItems = this.withSelectedActions(preview?.possible_conflicts);
          this.recentTallyItems = Array.isArray(preview?.recent_tally_changes_visible_after_reconnect)
            ? preview.recent_tally_changes_visible_after_reconnect
            : [];
          this.reconciliationCounts = (preview?.counts_by_entity_type || {}) as Record<string, number>;
          if (!this.pendingReconciliationItems.length && !this.failedReplayItems.length && !this.conflictItems.length && !this.alreadyPresentItems.length) {
            this.reconciliationModalOpen = false;
            this.reconciliationSummary = '';
            this.currentReconciliationSignature = '';
            this.closedReconciliationSignature = '';
          }
          this.syncMonitorService.refreshNow();
          this.companyContextService.refreshCompanies();
        },
        error: () => {
          this.reconciliationError = 'Could not sync the approved reconciliation items right now.';
        },
      });
  }

  updateReconciliationAction(item: ReconciliationPreviewItem, action: string): void {
    item.selectedAction = action;
  }

  previewKeys(item: ReconciliationPreviewItem): string[] {
    const keys = Object.keys(item?.preview_fields || {});
    const preferredOrder = [
      'VOUCHERTYPENAME',
      'VOUCHERNUMBER',
      'DATE',
      'PARTYLEDGERNAME',
      'TAXABLEAMOUNT',
      'GSTAMOUNT',
      'EXTRALEDGERAMOUNT',
      'VOUCHERTOTAL',
      'AMOUNT',
      'CHARGELEDGERS',
    ];
    return preferredOrder.filter((key) => keys.includes(key)).concat(keys.filter((key) => !preferredOrder.includes(key)));
  }

  tallyPreviewKeys(item: ReconciliationPreviewItem): string[] {
    return Object.keys(item?.tally_preview_fields || {});
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  private expandActiveSection(): void {
    for (const section of this.navSections) {
      if (this.isSectionActive(section)) {
        this.expandedSections[section.label] = true;
      }
    }
  }

  private withSelectedActions(items: unknown): ReconciliationPreviewItem[] {
    if (!Array.isArray(items)) {
      return [];
    }
    return items.map((item) => {
      const record = item as ReconciliationPreviewItem;
      return {
        ...record,
        selectedAction: record.selectedAction || record.default_selected_action || record.recommended_action || 'SKIP_FOR_NOW',
      };
    });
  }

  private asText(value: unknown): string {
    return typeof value === 'string' ? value : value == null ? '' : String(value);
  }

  itemStateMeta(item: ReconciliationPreviewItem): Array<{ label: string; value: string }> {
    const meta: Array<{ label: string; value: string }> = [];
    if (item.queue_status) {
      meta.push({ label: 'Queue Status', value: item.queue_status });
    }
    if (item.review_state) {
      meta.push({ label: 'Review State', value: item.review_state });
    }
    if (item.attempts != null) {
      const maxAttempts = item.max_attempts != null ? ` / ${item.max_attempts}` : '';
      meta.push({ label: 'Attempts', value: `${item.attempts}${maxAttempts}` });
    }
    if (item.next_attempt_at && item.queue_status === 'RETRY') {
      meta.push({ label: 'Next Retry', value: item.next_attempt_at });
    }
    return meta;
  }
}
