package com.tallybackend.web;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/cache/export")
public class TallyCacheExportController {

    private final CacheExportService cacheExportService;

    public TallyCacheExportController(CacheExportService cacheExportService) {
        this.cacheExportService = cacheExportService;
    }

    @GetMapping("/{category}/{dataset}")
    public ResponseEntity<byte[]> export(@PathVariable("category") String category,
                                         @PathVariable("dataset") String dataset,
                                         @RequestParam("format") String format,
                                         HttpServletRequest request) {
        CacheExportService.ExportFile exportFile =
                cacheExportService.export(category, dataset, format, resolveCompany(request), resolveParams(request));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(exportFile.getFileName())
                        .build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .contentType(org.springframework.http.MediaType.parseMediaType(exportFile.getContentType()))
                .contentLength(exportFile.getData().length)
                .body(exportFile.getData());
    }

    private Map<String, String> resolveParams(HttpServletRequest request) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            String key = entry.getKey();
            if ("format".equalsIgnoreCase(key) || "company".equalsIgnoreCase(key)) {
                continue;
            }
            String[] values = entry.getValue();
            if (values != null && values.length > 0 && values[0] != null) {
                params.put(key, values[0].trim());
            }
        }
        return params;
    }

    private String resolveCompany(HttpServletRequest request) {
        String company = request.getHeader("X-Company");
        if (company == null || company.trim().isEmpty()) {
            company = request.getParameter("company");
        }
        return company == null ? null : company.trim();
    }
}
