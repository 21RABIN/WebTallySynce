package com.tallybackend.web;

import com.tallybackend.cache.TallyPostWriteRefreshService;
import com.tallybackend.service.ConnectorGatewayService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/xml")
public class XmlApiController extends AbstractConnectorController {

    public XmlApiController(ConnectorGatewayService connectorGatewayService,
                            TallyPostWriteRefreshService tallyPostWriteRefreshService) {
        super(connectorGatewayService, tallyPostWriteRefreshService);
    }

    @PostMapping(value = "/execute", consumes = {
            MediaType.APPLICATION_XML_VALUE,
            MediaType.TEXT_XML_VALUE,
            MediaType.TEXT_PLAIN_VALUE,
            MediaType.APPLICATION_JSON_VALUE
    })
    public ResponseEntity<String> execute(HttpServletRequest request, @RequestBody(required = false) String body) {
        return post("/xml/execute", request, body);
    }

    @GetMapping("/trace/status")
    public ResponseEntity<String> traceStatus(HttpServletRequest request) {
        return get("/xml/trace/status", request);
    }

    @GetMapping("/trace/files")
    public ResponseEntity<String> traceFiles(HttpServletRequest request) {
        return get("/xml/trace/files", request);
    }

    @GetMapping("/trace/file")
    public ResponseEntity<String> traceFile(HttpServletRequest request) {
        return get("/xml/trace/file", request);
    }

    @GetMapping("/access/steps")
    public ResponseEntity<String> accessSteps(HttpServletRequest request) {
        return get("/xml/access/steps", request);
    }

    @GetMapping("/access/samples")
    public ResponseEntity<String> accessSamples(HttpServletRequest request) {
        return get("/xml/access/samples", request);
    }

    @GetMapping("/access/check")
    public ResponseEntity<String> accessCheck(HttpServletRequest request) {
        return get("/xml/access/check", request);
    }
}
