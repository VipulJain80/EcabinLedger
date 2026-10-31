package com.ecabin.ledger.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunable local reference-cache limits. Values are also overridable through app.reference-cache.*. */
@ConfigurationProperties(prefix = "app.reference-cache")
public class ReferenceCacheProperties {
    private Duration categoriesTtl = Duration.ofMinutes(2);
    private Duration areasTtl = Duration.ofMinutes(2);
    private Duration componentsTtl = Duration.ofMinutes(1);
    private Duration conditionsTtl = Duration.ofMinutes(1);
    private Duration severitiesTtl = Duration.ofMinutes(1);
    private Duration fleetsTtl = Duration.ofMinutes(2);
    private Duration aircraftTypesTtl = Duration.ofMinutes(5);
    private Duration publishedFormsTtl = Duration.ofMinutes(1);
    private Duration formOptionsTtl = Duration.ofMinutes(1);
    private Duration workflowStatusesTtl = Duration.ofMinutes(5);
    private long maximumEntries = 1000;
    private long maximumOptionEntries = 3000;

    public Duration getCategoriesTtl() { return categoriesTtl; }
    public void setCategoriesTtl(Duration value) { categoriesTtl = positive(value, "categories-ttl"); }
    public Duration getAreasTtl() { return areasTtl; }
    public void setAreasTtl(Duration value) { areasTtl = positive(value, "areas-ttl"); }
    public Duration getComponentsTtl() { return componentsTtl; }
    public void setComponentsTtl(Duration value) { componentsTtl = positive(value, "components-ttl"); }
    public Duration getConditionsTtl() { return conditionsTtl; }
    public void setConditionsTtl(Duration value) { conditionsTtl = positive(value, "conditions-ttl"); }
    public Duration getSeveritiesTtl() { return severitiesTtl; }
    public void setSeveritiesTtl(Duration value) { severitiesTtl = positive(value, "severities-ttl"); }
    public Duration getFleetsTtl() { return fleetsTtl; }
    public void setFleetsTtl(Duration value) { fleetsTtl = positive(value, "fleets-ttl"); }
    public Duration getAircraftTypesTtl() { return aircraftTypesTtl; }
    public void setAircraftTypesTtl(Duration value) { aircraftTypesTtl = positive(value, "aircraft-types-ttl"); }
    public Duration getPublishedFormsTtl() { return publishedFormsTtl; }
    public void setPublishedFormsTtl(Duration value) { publishedFormsTtl = positive(value, "published-forms-ttl"); }
    public Duration getFormOptionsTtl() { return formOptionsTtl; }
    public void setFormOptionsTtl(Duration value) { formOptionsTtl = positive(value, "form-options-ttl"); }
    public Duration getWorkflowStatusesTtl() { return workflowStatusesTtl; }
    public void setWorkflowStatusesTtl(Duration value) { workflowStatusesTtl = positive(value, "workflow-statuses-ttl"); }
    public long getMaximumEntries() { return maximumEntries; }
    public void setMaximumEntries(long value) { maximumEntries = positive(value, "maximum-entries"); }
    public long getMaximumOptionEntries() { return maximumOptionEntries; }
    public void setMaximumOptionEntries(long value) { maximumOptionEntries = positive(value, "maximum-option-entries"); }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive.");
        return value;
    }
    private static long positive(long value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be at least 1.");
        return value;
    }
}
