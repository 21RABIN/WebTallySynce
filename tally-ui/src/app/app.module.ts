import { HTTP_INTERCEPTORS, HttpClientModule } from '@angular/common/http';
import { NgModule } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { BrowserModule } from '@angular/platform-browser';

import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
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
import { AuthInterceptor } from './core/auth/auth.interceptor';
import { CompanyContextInterceptor } from './core/auth/company-context.interceptor';
import { ShellComponent } from './layout/shell/shell.component';

@NgModule({
  declarations: [
    AppComponent,
    ShellComponent,
    LoginComponent,
    DashboardHomeComponent,
    GroupsComponent,
    LedgersComponent,
    StockGroupsComponent,
    StockItemsComponent,
    UomsComponent,
    CurrenciesComponent,
    PaymentRemindersComponent,
    DayBookComponent,
    LedgerVouchersComponent,
    CompaniesReportComponent,
    BalanceSheetComponent,
    ProfitLossComponent,
    ReportsExplorerComponent,
    VoucherCreateComponent,
    BusinessConfigStudioComponent,
    IntegrationStudioComponent,
    CompanyCurrencyComponent,
    CompanyFeaturesComponent,
  ],
  imports: [
    BrowserModule,
    HttpClientModule,
    ReactiveFormsModule,
    FormsModule,
    AppRoutingModule,
  ],
  providers: [
    {
      provide: HTTP_INTERCEPTORS,
      useClass: AuthInterceptor,
      multi: true,
    },
    {
      provide: HTTP_INTERCEPTORS,
      useClass: CompanyContextInterceptor,
      multi: true,
    },
  ],
  bootstrap: [AppComponent],
})
export class AppModule {}
