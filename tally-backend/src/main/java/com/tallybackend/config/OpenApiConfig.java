package com.tallybackend.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomiser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final List<String> CONNECTOR_BACKED_API_PREFIXES = Arrays.asList(
            "/api/currencies",
            "/api/groups",
            "/api/ledger-groups",
            "/api/ledgers",
            "/api/cost-categories",
            "/api/cost-centres",
            "/api/projects",
            "/api/uoms",
            "/api/godowns",
            "/api/stock-groups",
            "/api/stock-categories",
            "/api/stock-items",
            "/api/boms",
            "/api/price-levels",
            "/api/price-lists",
            "/api/voucher-types",
            "/api/budgets",
            "/api/employees",
            "/api/employee-groups",
            "/api/pay-heads",
            "/api/attendance-types",
            "/api/companies",
            "/api/settings/",
            "/api/reports/",
            "/api/vouchers"
    );

    @Bean
    public OpenAPI tallyBackendOpenApi() {
        final String schemeName = "bearerAuth";
        return new OpenAPI()
                .info(new Info().title("Tally Backend API").version("0.1.0"))
                .components(new Components().addSecuritySchemes(
                        schemeName,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                ))
                .addSecurityItem(new SecurityRequirement().addList(schemeName));
    }

    @Bean
    public OpenApiCustomiser connectorViewQueryParamCustomiser() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }

            openApi.getPaths().forEach((path, pathItem) -> {
                if (!supportsConnectorApi(path) || pathItem == null) {
                    return;
                }
                addViewParameter(pathItem.getGet());
                addActionParameter(pathItem.getPost());
                addActionParameter(pathItem.getPut());
                addActionParameter(pathItem.getPatch());
                addActionParameter(pathItem.getDelete());
            });
        };
    }

    private boolean supportsConnectorApi(String path) {
        for (String prefix : CONNECTOR_BACKED_API_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void addViewParameter(Operation operation) {
        if (operation == null) {
            return;
        }

        if (hasParameter(operation, "view")) {
            return;
        }

        operation.addParametersItem(new Parameter()
                .in("query")
                .name("view")
                .description("Response view forwarded to the connector. Supported values: summary, full, raw.")
                .schema(new StringSchema()
                        ._default("summary")
                        .addEnumItem("summary")
                        .addEnumItem("full")
                        .addEnumItem("raw")));
    }

    private void addActionParameter(Operation operation) {
        if (operation == null || hasParameter(operation, "action")) {
            return;
        }

        operation.addParametersItem(new Parameter()
                .in("query")
                .name("action")
                .description(
                        "Write action forwarded to the connector. Voucher routes support Create, Alter, Cancel, and Delete. "
                                + "Master routes support Create, Alter, and Delete. Use Cancel only for vouchers when you "
                                + "want the Tally voucher to remain in cancelled state; use Delete when you want the XML "
                                + "request to ask Tally for a true delete instead of cancellation."
                )
                .schema(new StringSchema()
                        ._default("Create")
                        .addEnumItem("Create")
                        .addEnumItem("Alter")
                        .addEnumItem("Cancel")
                        .addEnumItem("Delete")));
    }

    private boolean hasParameter(Operation operation, String name) {
        if (operation == null || operation.getParameters() == null) {
            return false;
        }
        for (Parameter parameter : operation.getParameters()) {
            if (name.equalsIgnoreCase(parameter.getName())) {
                return true;
            }
        }
        return false;
    }
}
