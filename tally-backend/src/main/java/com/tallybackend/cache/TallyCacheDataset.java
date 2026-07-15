package com.tallybackend.cache;

public enum TallyCacheDataset {
    COMPANIES("companies"),
    GROUPS("groups"),
    LEDGERS("ledgers"),
    UOMS("uoms"),
    CURRENCIES("currencies"),
    STOCK_GROUPS("stock_groups"),
    STOCK_ITEMS("stock_items"),
    COMPANY_CURRENCY("company_currency"),
    COMPANY_FEATURES("company_features"),
    GST_REGISTRATION("gst_registration"),
    NUMBERING_RULES("numbering_rules"),
    TAX_RATE_TABLES("tax_rate_tables"),
    PRICE_STRUCTURES("price_structures"),
    STOCK_CONTROLS("stock_controls"),
    SECURITY_ROLES("security_roles"),
    UQC_MAPPINGS("uqc_mappings"),
    EINVOICE_SETTINGS("einvoice_settings"),
    EWAYBILL_SETTINGS("ewaybill_settings"),
    DAY_BOOK("day_book"),
    LEDGER_VOUCHERS("ledger_vouchers"),
    BALANCE_SHEET("balance_sheet"),
    PROFIT_LOSS("profit_loss"),
    STOCK_SUMMARY("stock_summary"),
    OUTSTANDING_RECEIVABLES("outstanding_receivables"),
    OUTSTANDING_PAYABLES("outstanding_payables"),
    BATCH_AVAILABILITY("batch_availability"),
    REPORT_PRICE_LISTS("report_price_lists"),
    BANK_RECO_STATUS("bank_reco_status"),
    TRIAL_BALANCE("trial_balance"),
    CASH_BOOK("cash_book"),
    BANK_BOOK("bank_book"),
    CASH_FLOW("cash_flow"),
    FUNDS_FLOW("funds_flow"),
    SALES_REGISTER("sales_register"),
    SALES_TREND("sales_trend"),
    PURCHASE_REGISTER("purchase_register"),
    JOURNAL_REGISTER("journal_register"),
    RECEIPT_REGISTER("receipt_register"),
    PAYMENT_REGISTER("payment_register"),
    GSTR_1("gstr_1"),
    GSTR_2("gstr_2"),
    GSTR_3B("gstr_3b"),
    STOCK_AGEING_ANALYSIS("stock_ageing_analysis"),
    MOVEMENT_ANALYSIS("movement_analysis"),
    REORDER_STATUS("reorder_status"),
    FORM_26Q("form_26q"),
    FORM_24Q("form_24q"),
    FORM_27EQ("form_27eq"),
    TDS_OUTSTANDINGS("tds_outstandings"),
    COST_CENTRE_BREAKUP("cost_centre_breakup"),
    RATIO_ANALYSIS("ratio_analysis");

    private final String key;

    TallyCacheDataset(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
