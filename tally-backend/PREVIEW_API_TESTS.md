# Tally XML Preview API Tests

This file contains copy-paste commands to test the Tally XML Preview APIs exposed by the backend.

Base URL:

```text
http://127.0.0.1:9090
```

## 1. Login And Get Token

Use the bootstrap admin user:

```bash
curl -X POST http://127.0.0.1:9090/api/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin@123","include_token":true}'
```

Sample response:

```json
{
  "message": "authenticated",
  "token_type": "Bearer",
  "expires_in": 3600,
  "user": {
    "username": "admin",
    "displayName": "Administrator",
    "roles": ["ADMIN"]
  },
  "access_token": "<TOKEN>"
}
```

Export the token:

```bash
export TOKEN='<TOKEN>'
```

## 2. Preview API Format

All XML preview APIs are `GET` APIs.

Request format:

```http
GET /api/tally/xml-preview/{entity}/{id}
Authorization: Bearer <TOKEN>
```

There is no request body for preview APIs.

Response format:

```json
{
  "entityType": "CUSTOMER",
  "entityId": 1,
  "xml": "<ENVELOPE>...</ENVELOPE>"
}
```

## 3. Tested Working APIs

### Customer Preview

```bash
curl http://127.0.0.1:9090/api/tally/xml-preview/customer/1 \
  -H "Authorization: Bearer $TOKEN"
```

### Supplier Preview

```bash
curl http://127.0.0.1:9090/api/tally/xml-preview/supplier/1 \
  -H "Authorization: Bearer $TOKEN"
```

### Product Preview

```bash
curl http://127.0.0.1:9090/api/tally/xml-preview/product/1 \
  -H "Authorization: Bearer $TOKEN"
```

### Stock Group Preview

```bash
curl http://127.0.0.1:9090/api/tally/xml-preview/stock-group/1 \
  -H "Authorization: Bearer $TOKEN"
```

### Sales Invoice Preview

```bash
curl http://127.0.0.1:9090/api/tally/xml-preview/sales-invoice/1 \
  -H "Authorization: Bearer $TOKEN"
```

### Purchase Invoice Preview

```bash
curl http://127.0.0.1:9090/api/tally/xml-preview/purchase-invoice/1 \
  -H "Authorization: Bearer $TOKEN"
```

### Payment Preview

```bash
curl http://127.0.0.1:9090/api/tally/xml-preview/payment/1 \
  -H "Authorization: Bearer $TOKEN"
```

## 4. Sample Verified Responses

### Customer

```json
{
  "entityType": "CUSTOMER",
  "entityId": 1,
  "xml": "<ENVELOPE>\n  <HEADER>\n    <TALLYREQUEST>Import Data</TALLYREQUEST>\n  </HEADER>\n  <BODY>\n    <IMPORTDATA>\n      <REQUESTDESC>\n        <REPORTNAME>All Masters</REPORTNAME>\n      </REQUESTDESC>\n      <REQUESTDATA>\n        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n          <LEDGER NAME=\"Customer 1\" ACTION=\"Create\">\n            <NAME>Customer 1</NAME>\n            <PARENT>Sundry Debtors</PARENT>\n            <PARTYGSTIN>29ABCDE1234F1Z5</PARTYGSTIN>\n            <GSTREGISTRATIONTYPE>Regular</GSTREGISTRATIONTYPE>\n            <STATENAME>Karnataka</STATENAME>\n            <EMAIL>customer1@example.com</EMAIL>\n            <PHONENUMBER>9876543210</PHONENUMBER>\n          </LEDGER>\n        </TALLYMESSAGE>\n      </REQUESTDATA>\n    </IMPORTDATA>\n  </BODY>\n</ENVELOPE>"
}
```

### Product

```json
{
  "entityType": "PRODUCT",
  "entityId": 1,
  "xml": "<ENVELOPE>\n  <HEADER>\n    <TALLYREQUEST>Import Data</TALLYREQUEST>\n  </HEADER>\n  <BODY>\n    <IMPORTDATA>\n      <REQUESTDESC>\n        <REPORTNAME>All Masters</REPORTNAME>\n      </REQUESTDESC>\n      <REQUESTDATA>\n        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n          <STOCKITEM NAME=\"Sample Product 1\" ACTION=\"Create\">\n            <NAME>Sample Product 1</NAME>\n            <PARENT>Primary</PARENT>\n            <BASEUNITS>Nos</BASEUNITS>\n            <HSNCODE>8471</HSNCODE>\n            <GSTAPPLICABLE>Applicable</GSTAPPLICABLE>\n            <TAXABILITY>Taxable</TAXABILITY>\n            <GSTRATE>18.00</GSTRATE>\n          </STOCKITEM>\n        </TALLYMESSAGE>\n      </REQUESTDATA>\n    </IMPORTDATA>\n  </BODY>\n</ENVELOPE>"
}
```

### Sales Invoice

```json
{
  "entityType": "SALES_INVOICE",
  "entityId": 1,
  "xml": "<ENVELOPE>\n  <HEADER>\n    <TALLYREQUEST>Import Data</TALLYREQUEST>\n  </HEADER>\n  <BODY>\n    <IMPORTDATA>\n      <REQUESTDESC>\n        <REPORTNAME>Vouchers</REPORTNAME>\n      </REQUESTDESC>\n      <REQUESTDATA>\n        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n          <VOUCHER VCHTYPE=\"Sales\" ACTION=\"Create\" OBJVIEW=\"Invoice Voucher View\">\n            <DATE>20260518</DATE>\n            <VOUCHERTYPENAME>Sales</VOUCHERTYPENAME>\n            <VOUCHERNUMBER>SI-1</VOUCHERNUMBER>\n            <PERSISTEDVIEW>Invoice Voucher View</PERSISTEDVIEW>\n            <ISINVOICE>Yes</ISINVOICE>\n            <PARTYLEDGERNAME>Customer 1</PARTYLEDGERNAME>\n            <LEDGERENTRIES.LIST>\n              <LEDGERNAME>Customer 1</LEDGERNAME>\n              <ISPARTYLEDGER>Yes</ISPARTYLEDGER>\n              <AMOUNT>-1180.00</AMOUNT>\n            </LEDGERENTRIES.LIST>\n            <LEDGERENTRIES.LIST>\n              <LEDGERNAME>Sales Account</LEDGERNAME>\n              <ISPARTYLEDGER>No</ISPARTYLEDGER>\n              <AMOUNT>1000.00</AMOUNT>\n            </LEDGERENTRIES.LIST>\n            <LEDGERENTRIES.LIST>\n              <LEDGERNAME>Output IGST</LEDGERNAME>\n              <ISPARTYLEDGER>No</ISPARTYLEDGER>\n              <AMOUNT>180.00</AMOUNT>\n            </LEDGERENTRIES.LIST>\n          </VOUCHER>\n        </TALLYMESSAGE>\n      </REQUESTDATA>\n    </IMPORTDATA>\n  </BODY>\n</ENVELOPE>"
}
```

### Payment

```json
{
  "entityType": "PAYMENT",
  "entityId": 1,
  "xml": "<ENVELOPE>\n  <HEADER>\n    <TALLYREQUEST>Import Data</TALLYREQUEST>\n  </HEADER>\n  <BODY>\n    <IMPORTDATA>\n      <REQUESTDESC>\n        <REPORTNAME>Vouchers</REPORTNAME>\n      </REQUESTDESC>\n      <REQUESTDATA>\n        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n          <VOUCHER VCHTYPE=\"Payment\" ACTION=\"Create\" OBJVIEW=\"Accounting Voucher View\">\n            <DATE>20260518</DATE>\n            <VOUCHERTYPENAME>Payment</VOUCHERTYPENAME>\n            <VOUCHERNUMBER>PAY-1</VOUCHERNUMBER>\n            <PERSISTEDVIEW>Accounting Voucher View</PERSISTEDVIEW>\n            <ISINVOICE>No</ISINVOICE>\n            <PARTYLEDGERNAME>Supplier 1</PARTYLEDGERNAME>\n            <LEDGERENTRIES.LIST>\n              <LEDGERNAME>Supplier 1</LEDGERNAME>\n              <ISPARTYLEDGER>Yes</ISPARTYLEDGER>\n              <AMOUNT>1180.00</AMOUNT>\n            </LEDGERENTRIES.LIST>\n            <LEDGERENTRIES.LIST>\n              <LEDGERNAME>Bank Account</LEDGERNAME>\n              <ISPARTYLEDGER>No</ISPARTYLEDGER>\n              <AMOUNT>-1180.00</AMOUNT>\n            </LEDGERENTRIES.LIST>\n          </VOUCHER>\n        </TALLYMESSAGE>\n      </REQUESTDATA>\n    </IMPORTDATA>\n  </BODY>\n</ENVELOPE>"
}
```

## 5. Seed Default Ledger Mappings

Voucher previews need ledger mappings. The defaults for `businessUnitId=1` can be seeded using:

```bash
curl -X POST http://127.0.0.1:9090/api/tally-mappings/ledgers/defaults/1 \
  -H "Authorization: Bearer $TOKEN"
```

## 6. Notes

- The preview APIs currently use mock ERP data from the temporary development adapter.
- These APIs do not send XML to Tally. They only generate and return the XML preview.
- To test with real ERP records later, replace the mock adapter logic in [DefaultErpEntityAdapterService.java](/home/user/Videos/TALLY/Tally_BackUp/tallyconnector/tally-backend/src/main/java/com/tallybackend/sync/service/DefaultErpEntityAdapterService.java:1).
