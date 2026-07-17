package com.tallybackend.web;

import com.tallybackend.cache.TallyPostWriteRefreshService;
import com.tallybackend.service.ConnectorGatewayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api")
public class MasterDataApiController extends AbstractConnectorController {

    public MasterDataApiController(ConnectorGatewayService connectorGatewayService,
                                   TallyPostWriteRefreshService tallyPostWriteRefreshService) {
        super(connectorGatewayService, tallyPostWriteRefreshService);
    }

    @GetMapping("/currencies")
    public ResponseEntity<String> currencies(HttpServletRequest request) { return get("/currencies", request); }
    @PostMapping("/currencies")
    public ResponseEntity<String> currenciesUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/currencies", request, body); }

    @GetMapping("/groups")
    public ResponseEntity<String> groups(HttpServletRequest request) { return get("/groups", request); }
    @PostMapping("/groups")
    public ResponseEntity<String> groupsUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/groups", request, body); }

    @GetMapping("/ledger-groups")
    public ResponseEntity<String> ledgerGroups(HttpServletRequest request) { return get("/ledger-groups", request); }
    @PostMapping("/ledger-groups")
    public ResponseEntity<String> ledgerGroupsUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/ledger-groups", request, body); }

    @GetMapping("/ledgers")
    public ResponseEntity<String> ledgers(HttpServletRequest request) { return get("/ledgers", request); }
    @PostMapping("/ledgers")
    public ResponseEntity<String> ledgersUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/ledgers", request, body); }

    @GetMapping("/cost-categories")
    public ResponseEntity<String> costCategories(HttpServletRequest request) { return get("/cost-categories", request); }
    @PostMapping("/cost-categories")
    public ResponseEntity<String> costCategoriesUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/cost-categories", request, body); }

    @GetMapping("/cost-centres")
    public ResponseEntity<String> costCentres(HttpServletRequest request) { return get("/cost-centres", request); }
    @PostMapping("/cost-centres")
    public ResponseEntity<String> costCentresUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/cost-centres", request, body); }

    @GetMapping("/projects")
    public ResponseEntity<String> projects(HttpServletRequest request) { return get("/projects", request); }
    @PostMapping("/projects")
    public ResponseEntity<String> projectsUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/projects", request, body); }

    @GetMapping("/uoms")
    public ResponseEntity<String> uoms(HttpServletRequest request) { return get("/uoms", request); }
    @PostMapping("/uoms")
    public ResponseEntity<String> uomsUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/uoms", request, body); }

    @GetMapping("/godowns")
    public ResponseEntity<String> godowns(HttpServletRequest request) { return get("/godowns", request); }
    @PostMapping("/godowns")
    public ResponseEntity<String> godownsUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/godowns", request, body); }

    @GetMapping("/stock-groups")
    public ResponseEntity<String> stockGroups(HttpServletRequest request) { return get("/stock-groups", request); }
    @PostMapping("/stock-groups")
    public ResponseEntity<String> stockGroupsUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/stock-groups", request, body); }

    @GetMapping("/stock-categories")
    public ResponseEntity<String> stockCategories(HttpServletRequest request) { return get("/stock-categories", request); }
    @PostMapping("/stock-categories")
    public ResponseEntity<String> stockCategoriesUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/stock-categories", request, body); }

    @GetMapping("/stock-items")
    public ResponseEntity<String> stockItems(HttpServletRequest request) { return get("/stock-items", request); }
    @PostMapping("/stock-items")
    public ResponseEntity<String> stockItemsUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/stock-items", request, body); }

    @GetMapping("/boms")
    public ResponseEntity<String> boms(HttpServletRequest request) { return get("/boms", request); }
    @PostMapping("/boms")
    public ResponseEntity<String> bomsUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/boms", request, body); }

    @GetMapping("/price-levels")
    public ResponseEntity<String> priceLevels(HttpServletRequest request) { return get("/price-levels", request); }
    @PostMapping("/price-levels")
    public ResponseEntity<String> priceLevelsUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/price-levels", request, body); }

    @GetMapping("/price-lists")
    public ResponseEntity<String> priceLists(HttpServletRequest request) { return get("/price-lists", request); }
    @PostMapping("/price-lists")
    public ResponseEntity<String> priceListsUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/price-lists", request, body); }

    @GetMapping("/voucher-types")
    public ResponseEntity<String> voucherTypes(HttpServletRequest request) { return get("/voucher-types", request); }
    @PostMapping("/voucher-types")
    public ResponseEntity<String> voucherTypesUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/voucher-types", request, body); }

    @GetMapping("/budgets")
    public ResponseEntity<String> budgets(HttpServletRequest request) { return get("/budgets", request); }
    @PostMapping("/budgets")
    public ResponseEntity<String> budgetsUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/budgets", request, body); }
}
