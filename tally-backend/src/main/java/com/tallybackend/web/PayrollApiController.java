package com.tallybackend.web;

import com.tallybackend.cache.TallyPostWriteRefreshService;
import com.tallybackend.service.ConnectorGatewayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api")
public class PayrollApiController extends AbstractConnectorController {

    public PayrollApiController(ConnectorGatewayService connectorGatewayService,
                                TallyPostWriteRefreshService tallyPostWriteRefreshService) {
        super(connectorGatewayService, tallyPostWriteRefreshService);
    }

    @GetMapping("/employees")
    public ResponseEntity<String> employees(HttpServletRequest request) {
        return get("/employees", request);
    }

    @PostMapping("/employees")
    public ResponseEntity<String> employeesUpsert(HttpServletRequest request,
                                                  @RequestBody(required = false) String body) {
        return postAndRefresh("/employees", request, body);
    }

    @GetMapping("/employee-groups")
    public ResponseEntity<String> employeeGroups(HttpServletRequest request) {
        return get("/employee-groups", request);
    }

    @PostMapping("/employee-groups")
    public ResponseEntity<String> employeeGroupsUpsert(HttpServletRequest request,
                                                       @RequestBody(required = false) String body) {
        return postAndRefresh("/employee-groups", request, body);
    }

    @GetMapping("/pay-heads")
    public ResponseEntity<String> payHeads(HttpServletRequest request) {
        return get("/pay-heads", request);
    }

    @PostMapping("/pay-heads")
    public ResponseEntity<String> payHeadsUpsert(HttpServletRequest request,
                                                 @RequestBody(required = false) String body) {
        return postAndRefresh("/pay-heads", request, body);
    }

    @GetMapping("/attendance-types")
    public ResponseEntity<String> attendanceTypes(HttpServletRequest request) {
        return get("/attendance-types", request);
    }

    @PostMapping("/attendance-types")
    public ResponseEntity<String> attendanceTypesUpsert(HttpServletRequest request,
                                                        @RequestBody(required = false) String body) {
        return postAndRefresh("/attendance-types", request, body);
    }
}
