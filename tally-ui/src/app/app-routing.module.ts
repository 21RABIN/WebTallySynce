import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { LoginComponent } from './features/auth/login/login.component';
import { DashboardHomeComponent } from './features/dashboard/dashboard-home/dashboard-home.component';
import { CompaniesReportComponent } from './features/reports/companies-report/companies-report.component';
import { BalanceSheetComponent } from './features/reports/balance-sheet/balance-sheet.component';
import { DayBookComponent } from './features/reports/day-book/day-book.component';
import { LedgerVouchersComponent } from './features/reports/ledger-vouchers/ledger-vouchers.component';
import { ProfitLossComponent } from './features/reports/profit-loss/profit-loss.component';
import { ReportsExplorerComponent } from './features/reports/reports-explorer/reports-explorer.component';
import { BusinessConfigStudioComponent } from './features/operations/business-config-studio/business-config-studio.component';
import { IntegrationStudioComponent } from './features/operations/integration-studio/integration-studio.component';
import { CompanyCurrencyComponent } from './features/settings/company-currency/company-currency.component';
import { CompanyFeaturesComponent } from './features/settings/company-features/company-features.component';
import { CurrenciesComponent } from './features/masters/currencies/currencies.component';
import { GroupsComponent } from './features/masters/groups/groups.component';
import { LedgersComponent } from './features/masters/ledgers/ledgers.component';
import { StockGroupsComponent } from './features/masters/stock-groups/stock-groups.component';
import { StockItemsComponent } from './features/masters/stock-items/stock-items.component';
import { UomsComponent } from './features/masters/uoms/uoms.component';
import { PaymentRemindersComponent } from './features/operations/payment-reminders/payment-reminders.component';
import { VoucherCreateComponent } from './features/vouchers/voucher-create/voucher-create.component';
import { AuthGuard } from './core/auth/auth.guard';
import { ShellComponent } from './layout/shell/shell.component';

const routes: Routes = [
  {
    path: 'login',
    component: LoginComponent,
  },
  {
    path: '',
    component: ShellComponent,
    canActivate: [AuthGuard],
    children: [
      {
        path: 'dashboard',
        component: DashboardHomeComponent,
      },
      {
        path: 'masters/groups',
        component: GroupsComponent,
      },
      {
        path: 'masters/ledgers',
        component: LedgersComponent,
      },
      {
        path: 'masters/stock-groups',
        component: StockGroupsComponent,
      },
      {
        path: 'masters/uoms',
        component: UomsComponent,
      },
      {
        path: 'masters/stock-items',
        component: StockItemsComponent,
      },
      {
        path: 'masters/currencies',
        component: CurrenciesComponent,
      },
      {
        path: 'reports/day-book',
        component: DayBookComponent,
      },
      {
        path: 'reports/ledger-vouchers',
        component: LedgerVouchersComponent,
      },
      {
        path: 'reports/companies',
        component: CompaniesReportComponent,
      },
      {
        path: 'reports/balance-sheet',
        component: BalanceSheetComponent,
      },
      {
        path: 'reports/profit-loss',
        component: ProfitLossComponent,
      },
      {
        path: 'reports/explorer',
        component: ReportsExplorerComponent,
      },
      {
        path: 'vouchers/create',
        component: VoucherCreateComponent,
      },
      {
        path: 'operations/payment-reminders',
        component: PaymentRemindersComponent,
      },
      {
        path: 'operations/business-config',
        component: BusinessConfigStudioComponent,
      },
      {
        path: 'operations/integration-studio',
        component: IntegrationStudioComponent,
      },
      {
        path: 'settings/company-currency',
        component: CompanyCurrencyComponent,
      },
      {
        path: 'settings/company-features',
        component: CompanyFeaturesComponent,
      },
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'dashboard',
      },
    ],
  },
  {
    path: '**',
    redirectTo: '',
  },
];

@NgModule({
  imports: [RouterModule.forRoot(routes, { useHash: true })],
  exports: [RouterModule],
})
export class AppRoutingModule {}
