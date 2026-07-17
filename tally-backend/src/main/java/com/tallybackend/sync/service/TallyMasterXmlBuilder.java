package com.tallybackend.sync.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
public class TallyMasterXmlBuilder {

    public String buildCustomerLedgerXml(Map<String, Object> customer) {
        return buildLedgerXml(
                ErpPayloadSupport.stringValue(customer, "name", "customerName"),
                ErpPayloadSupport.stringValue(customer, "ledgerGroup", "groupName", "parent", "tallyGroupName"),
                customer,
                "Sundry Debtors"
        );
    }

    public String buildSupplierLedgerXml(Map<String, Object> supplier) {
        return buildLedgerXml(
                ErpPayloadSupport.stringValue(supplier, "name", "supplierName"),
                ErpPayloadSupport.stringValue(supplier, "ledgerGroup", "groupName", "parent", "tallyGroupName"),
                supplier,
                "Sundry Creditors"
        );
    }

    public String buildProductStockItemXml(Map<String, Object> product) {
        String productName = ErpPayloadSupport.stringValue(product, "name", "productName");
        String uom = ErpPayloadSupport.stringValue(product, "uom", "unit", "unitName", "baseUnit");
        String group = ErpPayloadSupport.stringValue(product, "stockGroup", "groupName", "parent");
        String hsnCode = ErpPayloadSupport.stringValue(product, "hsnCode", "hsn");
        BigDecimal gstRate = ErpPayloadSupport.decimalValue(product, "gstRate", "taxRate");

        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <STOCKITEM NAME=\"").append(ErpPayloadSupport.xml(productName)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", productName, 6));
        xml.append(tag("PARENT", defaultString(group, "Primary"), 6));
        xml.append(tag("BASEUNITS", defaultString(uom, "Nos"), 6));
        if (hsnCode != null) {
            xml.append(tag("HSNCODE", hsnCode, 6));
        }
        if (gstRate != null) {
            xml.append(tag("GSTAPPLICABLE", "Applicable", 6));
            xml.append(tag("TAXABILITY", "Taxable", 6));
            xml.append(tag("GSTRATE", ErpPayloadSupport.amount(gstRate), 6));
        }
        xml.append("          </STOCKITEM>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildUnitXml(Map<String, Object> unit) {
        String name = ErpPayloadSupport.stringValue(unit, "name", "unitName", "uom");
        String baseUnits = defaultString(ErpPayloadSupport.stringValue(unit, "baseUnit", "symbol"), name);
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <UNIT NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", name, 6));
        xml.append(tag("BASEUNITS", baseUnits, 6));
        xml.append("          </UNIT>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildGroupXml(Map<String, Object> group) {
        String name = ErpPayloadSupport.stringValue(group, "name", "groupName");
        String parent = defaultString(ErpPayloadSupport.stringValue(group, "parent", "parentGroup"), "Primary");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <GROUP NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", name, 6));
        xml.append(tag("PARENT", parent, 6));
        xml.append("          </GROUP>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildStockGroupXml(Map<String, Object> payload) {
        String name = ErpPayloadSupport.stringValue(payload, "name", "stockGroupName");
        String parent = defaultString(ErpPayloadSupport.stringValue(payload, "parent", "parentGroup"), "Primary");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <STOCKGROUP NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", name, 6));
        xml.append(tag("PARENT", parent, 6));
        xml.append("          </STOCKGROUP>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildStockCategoryXml(Map<String, Object> payload) {
        String name = ErpPayloadSupport.stringValue(payload, "name", "stockCategoryName");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <STOCKCATEGORY NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", name, 6));
        xml.append("          </STOCKCATEGORY>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildGodownXml(Map<String, Object> payload) {
        String name = ErpPayloadSupport.stringValue(payload, "name", "godownName");
        String parent = ErpPayloadSupport.stringValue(payload, "parent", "parentGodown");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <GODOWN NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", name, 6));
        if (parent != null) {
            xml.append(tag("PARENT", parent, 6));
        }
        xml.append("          </GODOWN>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildCostCategoryXml(Map<String, Object> payload) {
        String name = ErpPayloadSupport.stringValue(payload, "name", "costCategoryName");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <COSTCATEGORY NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", name, 6));
        xml.append("          </COSTCATEGORY>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildCostCentreXml(Map<String, Object> payload) {
        String name = ErpPayloadSupport.stringValue(payload, "name", "costCentreName");
        String category = ErpPayloadSupport.stringValue(payload, "category", "costCategory");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <COSTCENTRE NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", name, 6));
        if (category != null) {
            xml.append(tag("CATEGORY", category, 6));
        }
        xml.append("          </COSTCENTRE>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildBomXml(Map<String, Object> payload) {
        String name = ErpPayloadSupport.stringValue(payload, "name", "bomName");
        String stockItem = ErpPayloadSupport.stringValue(payload, "stockItem", "itemName");
        BigDecimal qty = ErpPayloadSupport.decimalValue(payload, "quantity", "qty");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <BOM NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        if (stockItem != null) {
            xml.append(tag("STOCKITEMNAME", stockItem, 6));
        }
        if (qty != null) {
            xml.append(tag("BOMQTY", ErpPayloadSupport.amount(qty), 6));
        }
        xml.append("          </BOM>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildPriceLevelXml(Map<String, Object> payload) {
        String name = ErpPayloadSupport.stringValue(payload, "name", "priceLevelName");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <PRICELEVEL NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", name, 6));
        xml.append("          </PRICELEVEL>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildVoucherTypeXml(Map<String, Object> payload) {
        String name = ErpPayloadSupport.stringValue(payload, "name", "voucherTypeName");
        String parent = ErpPayloadSupport.stringValue(payload, "parent", "parentType");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <VOUCHERTYPE NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", name, 6));
        if (parent != null) {
            xml.append(tag("PARENT", parent, 6));
        }
        xml.append("          </VOUCHERTYPE>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildBudgetXml(Map<String, Object> payload) {
        String name = ErpPayloadSupport.stringValue(payload, "name", "budgetName");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <BUDGET NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", name, 6));
        xml.append("          </BUDGET>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildEmployeeXml(Map<String, Object> payload) {
        String name = ErpPayloadSupport.stringValue(payload, "name", "employeeName");
        String category = defaultString(ErpPayloadSupport.stringValue(payload, "category", "costCategory"), "Primary Cost Category");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <COSTCENTRE NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", name, 6));
        xml.append(tag("CATEGORY", category, 6));
        xml.append(tag("USEASEMPLOYEE", "Yes", 6));
        xml.append(tag("FORPAYROLL", "Yes", 6));
        String empId = ErpPayloadSupport.stringValue(payload, "employeeId", "code");
        if (empId != null) {
            xml.append(tag("EMPLOYEEID", empId, 6));
        }
        String aadhaar = ErpPayloadSupport.stringValue(payload, "aadhaar", "aadhaarNumber");
        if (aadhaar != null) {
            xml.append(tag("AADHAARNUMBER", aadhaar, 6));
        }
        String uan = ErpPayloadSupport.stringValue(payload, "uan", "uanNumber");
        if (uan != null) {
            xml.append(tag("UANNUMBER", uan, 6));
        }
        xml.append("          </COSTCENTRE>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildEmployeeGroupXml(Map<String, Object> payload) {
        String name = ErpPayloadSupport.stringValue(payload, "name", "employeeGroupName");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <COSTCENTRE NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", name, 6));
        xml.append(tag("ISEMPLOYEEGROUP", "Yes", 6));
        xml.append(tag("FORPAYROLL", "Yes", 6));
        xml.append("          </COSTCENTRE>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildPayHeadXml(Map<String, Object> payload) {
        String name = ErpPayloadSupport.stringValue(payload, "name", "payHeadName");
        String parent = defaultString(ErpPayloadSupport.stringValue(payload, "parent", "ledgerGroup", "groupName"), "Primary");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <LEDGER NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", name, 6));
        xml.append(tag("PARENT", parent, 6));
        xml.append(tag("FORPAYROLL", "Yes", 6));
        xml.append("          </LEDGER>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildAttendanceTypeXml(Map<String, Object> payload) {
        String name = ErpPayloadSupport.stringValue(payload, "name", "attendanceTypeName");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <ATTENDANCETYPE NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", name, 6));
        xml.append("          </ATTENDANCETYPE>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    private String buildLedgerXml(String name, String parent, Map<String, Object> source, String defaultParent) {
        String gstin = ErpPayloadSupport.stringValue(source, "gstin", "gstNumber");
        String stateName = ErpPayloadSupport.stringValue(source, "stateName", "state");
        String email = ErpPayloadSupport.stringValue(source, "email", "emailId");
        String phone = ErpPayloadSupport.stringValue(source, "phone", "mobile", "mobileNumber");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart("All Masters"));
        xml.append("          <LEDGER NAME=\"").append(ErpPayloadSupport.xml(name)).append("\" ACTION=\"Create\">\n");
        xml.append(tag("NAME", name, 6));
        xml.append(tag("PARENT", defaultString(parent, defaultParent), 6));
        if (gstin != null) {
            xml.append(tag("PARTYGSTIN", gstin, 6));
            xml.append(tag("GSTREGISTRATIONTYPE", "Regular", 6));
        }
        if (stateName != null) {
            xml.append(tag("STATENAME", stateName, 6));
        }
        if (email != null) {
            xml.append(tag("EMAIL", email, 6));
        }
        if (phone != null) {
            xml.append(tag("PHONENUMBER", phone, 6));
        }
        xml.append("          </LEDGER>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    private String envelopeStart(String reportName) {
        return "<ENVELOPE>\n" +
                "  <HEADER>\n" +
                "    <TALLYREQUEST>Import Data</TALLYREQUEST>\n" +
                "  </HEADER>\n" +
                "  <BODY>\n" +
                "    <IMPORTDATA>\n" +
                "      <REQUESTDESC>\n" +
                "        <REPORTNAME>" + reportName + "</REPORTNAME>\n" +
                "      </REQUESTDESC>\n" +
                "      <REQUESTDATA>\n" +
                "        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n";
    }

    private String envelopeEnd() {
        return "        </TALLYMESSAGE>\n" +
                "      </REQUESTDATA>\n" +
                "    </IMPORTDATA>\n" +
                "  </BODY>\n" +
                "</ENVELOPE>";
    }

    private String tag(String tag, String value, int level) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < level; i++) {
            indent.append("  ");
        }
        return indent + "<" + tag + ">" + ErpPayloadSupport.xml(defaultString(value, "")) + "</" + tag + ">\n";
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}
