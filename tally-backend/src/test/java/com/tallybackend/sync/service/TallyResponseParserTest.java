package com.tallybackend.sync.service;

import com.tallybackend.sync.dto.TallyParsedResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TallyResponseParserTest {

    private final TallyResponseParser parser = new TallyResponseParser();

    @Test
    void parsesSuccessfulImportCountersAndGuid() {
        String xml = "<ENVELOPE><BODY><DATA><IMPORTRESULT>" +
                "<CREATED>1</CREATED><ALTERED>0</ALTERED><DELETED>0</DELETED>" +
                "<ERRORS>0</ERRORS><CANCELLED>0</CANCELLED><LASTVCHID>45</LASTVCHID><GUID>abc-123</GUID>" +
                "</IMPORTRESULT></DATA></BODY></ENVELOPE>";

        TallyParsedResponse parsed = parser.parse(xml);

        assertTrue(parsed.isSuccess());
        assertEquals(1, parsed.getCreated());
        assertEquals(45 + "", parsed.getLastVchId());
        assertEquals("abc-123", parsed.getTallyGuid());
    }

    @Test
    void marksFailureWhenLineErrorPresent() {
        String xml = "<ENVELOPE><BODY><DATA><IMPORTRESULT>" +
                "<CREATED>0</CREATED><ERRORS>1</ERRORS><LINEERROR>Ledger missing</LINEERROR>" +
                "</IMPORTRESULT></DATA></BODY></ENVELOPE>";

        TallyParsedResponse parsed = parser.parse(xml);

        assertFalse(parsed.isSuccess());
        assertEquals(1, parsed.getErrors());
        assertEquals("Ledger missing", parsed.getLineError());
    }
}
