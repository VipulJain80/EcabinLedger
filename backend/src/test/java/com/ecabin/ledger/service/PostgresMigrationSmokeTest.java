package com.ecabin.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.flywaydb.core.Flyway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Explicit opt-in smoke test for applying the real migrations to the configured local PostgreSQL schema. */
class PostgresMigrationSmokeTest {
    private static final String SCHEMA = System.getenv().getOrDefault("DB_SCHEMA", "ecabin_ledger");
    private static final Set<String> REQUIRED_TABLES = Set.of("operators_mst", "fleets_mst", "aircraft_mst", "operator_memberships_mst",
        "operator_membership_teams_mst", "defects_dtl", "inspections_dtl", "corrective_actions_dtl", "verifications_dtl",
        "closure_approvals_dtl", "attachments_dtl", "notifications_dtl", "defect_events_dtl", "maintenance_teams_mst", "maintenance_sla_policies_mst", "user_login_events_dtl", "workflow_status_master",
        "cabin_areas_mst", "area_maintenance_team_mappings_mst", "defect_workflow_config_mst", "defect_categories_mst", "cabin_components_mst", "defect_conditions_mst", "component_conditions_mst", "configuration_imports_dtl", "navigation_menu_items_mst", "operator_roles_mst", "defect_severities_mst", "permissions_mst", "operational_forms_dtl", "operational_form_versions_dtl", "operational_form_submissions_dtl", "defect_workflow_versions_dtl", "api_rate_limit_policies_mst", "api_rate_limit_usage_dtl", "api_rate_limit_public_usage_dtl", "api_rate_limit_policy_events_dtl", "operational_form_areas_mst", "operational_form_version_areas_dtl", "defect_reference_counters_dtl", "report_routines_mst", "report_routine_parameters_mst", "report_templates_mst", "report_template_filters_mst", "report_template_columns_mst", "report_lookup_sources_mst", "reports_mst", "report_filters_mst", "report_columns_mst", "report_role_access_mst", "report_query_events_mst", "email_notification_settings_mst", "email_notification_event_settings_mst", "email_outbox_dtl", "email_outbox_attempts_dtl", "flyway_schema_history");
    private static final Set<String> EXPECTED_APPLICATION_TABLES = Set.of("operators_mst", "fleets_mst", "aircraft_mst", "operator_memberships_mst",
        "operator_membership_teams_mst", "maintenance_teams_mst", "cabin_areas_mst", "defect_categories_mst",
        "cabin_components_mst", "defect_conditions_mst", "component_conditions_mst", "operator_roles_mst", "navigation_menu_items_mst", "area_maintenance_team_mappings_mst", "maintenance_sla_policies_mst", "defect_workflow_config_mst", "defect_workflow_versions_dtl",
        "defects_dtl", "defect_events_dtl", "inspections_dtl", "corrective_actions_dtl", "attachments_dtl", "notifications_dtl",
        "configuration_imports_dtl", "defect_severities_mst", "permissions_mst", "operational_forms_dtl", "operational_form_versions_dtl", "operational_form_submissions_dtl",
        "verifications_dtl", "closure_approvals_dtl", "api_rate_limit_policies_mst", "api_rate_limit_usage_dtl", "api_rate_limit_public_usage_dtl", "api_rate_limit_policy_events_dtl", "operational_form_areas_mst", "operational_form_version_areas_dtl", "user_login_events_dtl", "defect_reference_counters_dtl", "workflow_status_master", "report_routines_mst", "report_routine_parameters_mst", "report_templates_mst", "report_template_filters_mst", "report_template_columns_mst", "report_lookup_sources_mst", "reports_mst", "report_filters_mst", "report_columns_mst", "report_role_access_mst", "report_query_events_mst", "email_notification_settings_mst", "email_notification_event_settings_mst", "email_outbox_dtl", "email_outbox_attempts_dtl");
    private static final Set<String> SOFT_DELETE_TABLES = Set.of("operators_mst", "fleets_mst", "aircraft_mst", "operator_memberships_mst",
        "operator_membership_teams_mst", "defects_dtl", "inspections_dtl", "corrective_actions_dtl", "verifications_dtl",
        "closure_approvals_dtl", "attachments_dtl", "notifications_dtl", "maintenance_teams_mst", "area_maintenance_team_mappings_mst",
        "defect_categories_mst", "cabin_areas_mst", "cabin_components_mst", "defect_conditions_mst", "component_conditions_mst", "navigation_menu_items_mst", "operator_roles_mst", "defect_severities_mst", "permissions_mst", "operational_forms_dtl", "api_rate_limit_policies_mst");

    @Test
    void createsProjectSchemaAndAppliesFlywayMigrations() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("ECABIN_DB_VERIFY")),
            "Set ECABIN_DB_VERIFY=true to apply migrations to the explicitly configured PostgreSQL schema.");
        String url = requiredEnv("SPRING_DATASOURCE_URL");
        String username = requiredEnv("SPRING_DATASOURCE_USERNAME");
        String password = requiredEnv("SPRING_DATASOURCE_PASSWORD");
        verifySchemaIsSafeToUse(url, username, password);

        Flyway.configure()
            .dataSource(url, username, password)
            .locations("classpath:db/migration")
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .createSchemas(true)
            .cleanDisabled(true)
            .load()
            .migrate();

        try (var connection = DriverManager.getConnection(url, username, password);
             var query = connection.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=?")) {
            query.setString(1, SCHEMA);
            try (var rows = query.executeQuery()) {
                Set<String> actual = new java.util.HashSet<>();
                while (rows.next()) actual.add(rows.getString(1));
                assertTrue(actual.containsAll(REQUIRED_TABLES), "The project schema must contain all migrated operational tables.");
                Set<String> applicationTables = new java.util.HashSet<>(actual);
                applicationTables.remove("flyway_schema_history");
                assertEquals(EXPECTED_APPLICATION_TABLES, applicationTables, "Application tables must match the inventory; workflow_status_master is the explicitly required global-catalog naming exception, and Flyway history remains unchanged.");
                assertTrue(!actual.contains("legacy_cabin_zones_archive"), "The retired cabin-zones table and its temporary archive must be removed.");
                assertTrue(!actual.contains("cabin_zones_mst"), "The retired cabin-zones master must not remain under its application name.");
                assertTrue(actual.stream().noneMatch(name -> name.endsWith("_mst_mst") || name.endsWith("_dtl_dtl") || name.endsWith("_mst_dtl") || name.endsWith("_dtl_mst")), "No table may have a duplicated or conflicting suffix.");
                assertEquals(72, appliedMigrationCount(connection), "All versioned migrations, including V72, must be recorded as applied.");
                try (var formAnswerColumn=connection.prepareStatement("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='operational_form_submissions_dtl' AND column_name='submitted_answers_json' AND data_type='jsonb' AND is_nullable='YES'")) {
                    formAnswerColumn.setString(1,SCHEMA);
                    try(var formAnswerRows=formAnswerColumn.executeQuery()){formAnswerRows.next();assertEquals(1,formAnswerRows.getInt(1),"V71 must add a nullable generic answer snapshot without rewriting old rows.");}
                }
                try (var optionalSnapshots=connection.prepareStatement("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid=?::regclass AND conname='operational_form_submission_snapshot_report_check'")) {
                    optionalSnapshots.setString(1,SCHEMA+".operational_form_submissions_dtl");
                    try(var snapshotRows=optionalSnapshots.executeQuery()) { assertTrue(snapshotRows.next()); String definition=snapshotRows.getString(1); assertFalse(definition.contains("snapshot_component_id IS NOT NULL")); assertFalse(definition.contains("snapshot_condition_id IS NOT NULL")); }
                }
                try (var emailObjects = connection.prepareStatement("SELECT (SELECT count(*) FROM pg_constraint WHERE conrelid=?::regclass AND conname='defect_events_operator_id_id_uq' AND contype='u'),(SELECT count(*) FROM pg_constraint WHERE conrelid=?::regclass AND conname='email_outbox_source_event_fk' AND contype='f'),(SELECT count(*) FROM pg_constraint WHERE conrelid=?::regclass AND conname='email_outbox_idempotency_uq' AND contype='u'),(SELECT count(*) FROM pg_indexes WHERE schemaname=? AND indexname IN ('email_outbox_due_idx','email_outbox_expired_lease_idx','email_outbox_tenant_status_idx','email_outbox_event_idx')),(SELECT count(DISTINCT trigger_name) FROM information_schema.triggers WHERE trigger_schema=? AND event_object_table IN ('email_notification_settings_mst','email_notification_event_settings_mst','email_outbox_dtl','email_outbox_attempts_dtl') AND trigger_name LIKE 'audit_email_%'),(SELECT count(DISTINCT trigger_name) FROM information_schema.triggers WHERE trigger_schema=? AND trigger_name IN ('email_global_setting_audit','email_event_setting_audit'))")) {
                    emailObjects.setString(1, SCHEMA + ".defect_events_dtl"); emailObjects.setString(2, SCHEMA + ".email_outbox_dtl"); emailObjects.setString(3, SCHEMA + ".email_outbox_dtl"); emailObjects.setString(4, SCHEMA); emailObjects.setString(5, SCHEMA); emailObjects.setString(6, SCHEMA);
                    try (var emailRows = emailObjects.executeQuery()) { emailRows.next(); assertEquals(1, emailRows.getInt(1), "V62 must add the tenant composite event key only once."); assertEquals(1, emailRows.getInt(2), "Outbox source events must use a tenant-safe FK."); assertEquals(1, emailRows.getInt(3), "Outbox enqueueing must be idempotent per event, recipient and type."); assertEquals(4, emailRows.getInt(4), "Worker claim and operational paths must have their supporting indexes."); assertEquals(4, emailRows.getInt(5), "Email settings and worker writes must use the standard audit metadata trigger."); assertEquals(2, emailRows.getInt(6), "Switch changes must be appended to the existing audit event stream."); }
                }
                try (var emailSettings = connection.prepareStatement("SELECT (SELECT count(*) FROM email_notification_settings_mst WHERE is_enabled),(SELECT count(*) FROM email_notification_event_settings_mst WHERE is_enabled),(SELECT count(*) FROM email_notification_event_settings_mst),(SELECT count(*) FROM operators_mst),(SELECT count(*) FROM email_notification_settings_mst),(SELECT count(*) FROM email_outbox_dtl),(SELECT count(*) FROM email_outbox_attempts_dtl)")) {
                    try (var emailRows = emailSettings.executeQuery()) { emailRows.next(); assertEquals(0, emailRows.getInt(1), "Global email delivery must default to disabled."); assertEquals(0, emailRows.getInt(2), "All event delivery switches must default to disabled."); assertEquals(14L * emailRows.getLong(4), emailRows.getLong(3), "The supported event catalog must be seeded for existing operators."); assertEquals(emailRows.getLong(4), emailRows.getLong(5), "Every existing operator must have one global email settings row."); assertEquals(0, emailRows.getInt(6), "Migrations must not enqueue email jobs."); assertEquals(0, emailRows.getInt(7), "Migrations must not fabricate delivery attempts."); }
                }
                try (var statuses = connection.prepareStatement("SELECT (SELECT count(*) FROM workflow_status_master WHERE module_code='DEFECT'),(SELECT count(*) FROM workflow_status_master WHERE module_code='DEFECT' AND is_active),(SELECT count(*) FROM workflow_status_master WHERE module_code='DEFECT' AND is_initial),(SELECT count(*) FROM workflow_status_master WHERE module_code='DEFECT' AND is_terminal),(SELECT count(*) FROM pg_indexes WHERE schemaname=? AND tablename='workflow_status_master' AND indexname='workflow_status_module_active_order_idx'),(SELECT count(DISTINCT trigger_name) FROM information_schema.triggers WHERE trigger_schema=? AND event_object_table='defects_dtl' AND trigger_name='defects_status_master_active')")) {
                    statuses.setString(1, SCHEMA); statuses.setString(2, SCHEMA);
                    try (var statusRows = statuses.executeQuery()) { statusRows.next(); assertEquals(15, statusRows.getInt(1), "V69 adds the returned-for-correction status to the 14 stable defect statuses."); assertEquals(15, statusRows.getInt(2), "All seeded defect statuses start active."); assertEquals(1, statusRows.getInt(3), "Reported is the only initial defect status."); assertEquals(2, statusRows.getInt(4), "Historical rejected and cancelled statuses remain terminal; Closed can be reopened."); assertEquals(1, statusRows.getInt(5), "The module/active/order lookup index must exist."); assertEquals(1, statusRows.getInt(6), "The active status guard must be installed on defect writes."); }
                }
                try (var codes = connection.prepareStatement("SELECT status_code FROM workflow_status_master WHERE module_code='DEFECT'")) {
                    Set<String> actualCodes = new java.util.HashSet<>();
                    try (var codeRows = codes.executeQuery()) { while (codeRows.next()) actualCodes.add(codeRows.getString(1)); }
                    assertEquals(Set.of("REPORTED","UNDER_REVIEW","REWORK","INSPECTION_REQUIRED","INSPECTION_IN_PROGRESS","INSPECTION_COMPLETE","ACTION_ASSIGNED","IN_PROGRESS","AWAITING_VERIFICATION","VERIFIED","APPROVAL_REQUIRED","CLOSED","REJECTED","CANCELLED","REOPENED"), actualCodes, "The catalog must seed the requested stable status code set.");
                }
                try (var reworkConstraint = connection.prepareStatement("SELECT count(*) FROM pg_constraint WHERE conrelid=?::regclass AND conname='defects_status_check' AND contype='c' AND pg_get_constraintdef(oid) LIKE '%REWORK%'")) {
                    reworkConstraint.setString(1, SCHEMA + ".defects_dtl");
                    try (var reworkRows = reworkConstraint.executeQuery()) { reworkRows.next(); assertEquals(1, reworkRows.getInt(1), "V69 must extend the existing defect status constraint rather than bypassing it."); }
                }
                try (var badgeColumns = connection.prepareStatement("SELECT (SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='workflow_status_master' AND column_name IN ('background_color','text_color','border_color','icon','display_style')),(SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='defect_severities_mst' AND column_name IN ('background_color','text_color','border_color','icon','display_style')),(SELECT count(*) FROM workflow_status_master WHERE module_code='DEFECT' AND display_style='SOLID' AND background_color ~ '^#[0-9A-Fa-f]{6}$' AND text_color ~ '^#[0-9A-Fa-f]{6}$' AND border_color ~ '^#[0-9A-Fa-f]{6}$')")) {
                    badgeColumns.setString(1, SCHEMA); badgeColumns.setString(2, SCHEMA);
                    try (var badgeRows = badgeColumns.executeQuery()) { badgeRows.next(); assertEquals(5, badgeRows.getInt(1), "V45 must make all badge appearance attributes available in Workflow Status Master."); assertEquals(5, badgeRows.getInt(2), "V45 must make all badge appearance attributes available in Severity Master."); assertEquals(15, badgeRows.getInt(3), "Every defect status, including REWORK, must receive a valid solid badge style."); }
                }
                try (var constraints = connection.prepareStatement("SELECT (SELECT count(*) FROM information_schema.table_constraints WHERE table_schema=? AND table_name='workflow_status_master' AND constraint_name='workflow_status_module_code_uq' AND constraint_type='UNIQUE'),(SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='workflow_status_master' AND column_name='alias_name' AND is_nullable='YES')")) {
                    constraints.setString(1, SCHEMA); constraints.setString(2, SCHEMA);
                    try (var constraintRows = constraints.executeQuery()) { constraintRows.next(); assertEquals(1, constraintRows.getInt(1), "Status codes must be unique within a module."); assertEquals(1, constraintRows.getInt(2), "Aliases remain nullable and non-unique."); }
                }
                try (var referenceObjects = connection.prepareStatement("SELECT (SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='cabin_areas_mst' AND column_name='shortcode' AND is_nullable='NO'),(SELECT count(*) FROM information_schema.table_constraints WHERE table_schema=? AND table_name='cabin_areas_mst' AND constraint_name='cabin_areas_operator_shortcode_key' AND constraint_type='UNIQUE'),(SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='defects_dtl' AND column_name='reference' AND character_maximum_length=48),(SELECT count(*) FROM information_schema.tables WHERE table_schema=? AND table_name='defect_reference_counters_dtl'),(SELECT count(*) FROM information_schema.table_constraints WHERE table_schema=? AND table_name='defect_reference_counters_dtl' AND constraint_type='PRIMARY KEY'),(SELECT count(*) FROM pg_indexes WHERE schemaname=? AND tablename='defects_dtl' AND indexdef ILIKE '%UNIQUE% (operator_id, reference)%')")) {
                    for (int i=1;i<=6;i++) referenceObjects.setString(i, SCHEMA);
                    try (var referenceRows=referenceObjects.executeQuery()) { referenceRows.next(); assertEquals(1,referenceRows.getInt(1),"Area shortcodes must be required."); assertEquals(1,referenceRows.getInt(2),"Area shortcodes must be unique within each tenant."); assertEquals(1,referenceRows.getInt(3),"The reference column must fit the date/shortcode/sequence format."); assertEquals(1,referenceRows.getInt(4),"The atomic defect counter table must exist."); assertEquals(1,referenceRows.getInt(5),"The counter key must isolate tenant/date/shortcode sequences."); assertEquals(1,referenceRows.getInt(6),"Defect references must retain tenant-scoped uniqueness."); }
                }
                try (var loginObjects = connection.prepareStatement("SELECT (SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='operator_memberships_mst' AND column_name='last_login_at'),(SELECT count(*) FROM information_schema.triggers WHERE trigger_schema=? AND event_object_table='user_login_events_dtl' AND trigger_name IN ('user_login_events_append_only','user_login_events_no_truncate')),(SELECT count(*) FROM information_schema.table_constraints WHERE table_schema=? AND table_name='user_login_events_dtl' AND constraint_name='user_login_events_membership_fk' AND constraint_type='FOREIGN KEY'),(SELECT count(*) FROM pg_indexes WHERE schemaname=? AND indexname IN ('operator_membership_last_login_idx','user_login_events_tenant_time_idx','user_login_events_membership_time_idx'))")) {
                    for (int i=1;i<=4;i++) loginObjects.setString(i, SCHEMA);
                    try (var loginRows=loginObjects.executeQuery()) { loginRows.next(); assertEquals(1,loginRows.getInt(1),"Memberships must expose the successful-login projection."); assertEquals(2,loginRows.getInt(2),"Login event UPDATE/DELETE and TRUNCATE protections must be installed."); assertEquals(1,loginRows.getInt(3),"Login history must be linked to its tenant-owned membership."); assertEquals(3,loginRows.getInt(4),"Login history and last-login queries must have tenant-scoped indexes."); }
                }
                try(var closureRoute=connection.prepareStatement("SELECT 1 FROM information_schema.columns WHERE table_schema=? AND table_name='operators_mst' AND column_name='default_closure_team_id'")){
                    closureRoute.setString(1,SCHEMA);try(var closureRows=closureRoute.executeQuery()){assertTrue(closureRows.next(),"V35 must add an independent tenant closure-review route.");}
                }
                try(var attachmentLimit=connection.prepareStatement("SELECT count(*) FROM pg_constraint WHERE conrelid=?::regclass AND contype='c' AND pg_get_constraintdef(oid) ILIKE '%size_bytes%' AND pg_get_constraintdef(oid) ILIKE '%20971520%'")){
                    attachmentLimit.setString(1,SCHEMA+".attachments_dtl");try(var attachmentLimitRows=attachmentLimit.executeQuery()){attachmentLimitRows.next();assertEquals(1,attachmentLimitRows.getInt(1),"V70 must permit at most 20 MiB per attachment while retaining a database size constraint.");}
                }
            }
            try(var policies=connection.prepareStatement("SELECT status_key,is_configured,max_future_days,allow_past FROM maintenance_sla_policies_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' ORDER BY CASE status_key WHEN 'WORK_IN_PROGRESS' THEN 1 WHEN 'ON_HOLD' THEN 2 ELSE 3 END")){
                try(var rows=policies.executeQuery()){
                    assertTrue(rows.next());assertEquals("WORK_IN_PROGRESS",rows.getString("status_key"));assertTrue(rows.getBoolean("is_configured"));assertEquals(15,rows.getInt("max_future_days"));assertFalse(rows.getBoolean("allow_past"));
                    assertTrue(rows.next());assertEquals("ON_HOLD",rows.getString("status_key"));assertTrue(rows.getBoolean("is_configured"));assertEquals(180,rows.getInt("max_future_days"));assertFalse(rows.getBoolean("allow_past"));
                    // Close Submitted is administrator-configurable; only its seeded presence and uniqueness are stable here.
                    assertTrue(rows.next());assertEquals("CLOSE_SUBMITTED",rows.getString("status_key"));assertFalse(rows.next());
                }
            }
            try (var objects = connection.prepareStatement("SELECT (SELECT count(*) FROM information_schema.table_constraints WHERE table_schema=? AND constraint_type='FOREIGN KEY'), (SELECT count(*) FROM information_schema.triggers WHERE trigger_schema=? AND event_object_table='defect_events_dtl' AND trigger_name='defect_events_append_only'), (SELECT count(*) FROM information_schema.triggers WHERE trigger_schema=? AND event_object_table='operational_form_versions_dtl' AND trigger_name='operational_form_version_immutable'), (SELECT count(*) FROM information_schema.triggers WHERE trigger_schema=? AND event_object_table='operational_form_submissions_dtl' AND trigger_name='operational_form_submission_immutable')")) {
                for (int i=1;i<=4;i++) objects.setString(i,SCHEMA);
                try (var rows=objects.executeQuery()) { rows.next(); assertTrue(rows.getInt(1)>0,"Tenant and workflow foreign keys must remain attached after table renames."); assertEquals(2,rows.getInt(2),"Append-only audit triggers for UPDATE and DELETE must remain attached to the renamed event table."); assertEquals(2,rows.getInt(3),"Published-version immutability triggers for UPDATE and DELETE must remain attached."); assertEquals(2,rows.getInt(4),"Submission immutability triggers for UPDATE and DELETE must remain attached."); }
            }
            try(var formSnapshot=connection.prepareStatement("SELECT (SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='operational_form_versions_dtl' AND column_name IN ('form_name','area_id','purpose','workflow_stage')),(SELECT count(*) FROM information_schema.triggers WHERE trigger_schema=? AND event_object_table='operational_form_versions_dtl' AND trigger_name='operational_form_version_mapping_snapshot')")){
                formSnapshot.setString(1,SCHEMA);formSnapshot.setString(2,SCHEMA);
                try(var rows=formSnapshot.executeQuery()){rows.next();assertEquals(4,rows.getInt(1),"V37 must snapshot each version's name, area, purpose and stage.");assertEquals(1,rows.getInt(2),"New SQL-created versions must receive their mapping snapshot.");}
            }
            try(var areaMappings=connection.prepareStatement("SELECT (SELECT count(*) FROM operational_forms_dtl f WHERE NOT EXISTS(SELECT 1 FROM operational_form_areas_mst m WHERE m.operator_id=f.operator_id AND m.form_id=f.id)),(SELECT count(*) FROM operational_form_versions_dtl v WHERE v.status='PUBLISHED' AND NOT EXISTS(SELECT 1 FROM operational_form_version_areas_dtl va WHERE va.operator_id=v.operator_id AND va.form_id=v.form_id AND va.form_version_id=v.id)),(SELECT count(DISTINCT trigger_name) FROM information_schema.triggers WHERE trigger_schema=? AND event_object_table='operational_form_version_areas_dtl' AND trigger_name='operational_form_version_areas_immutable')")){
                areaMappings.setString(1,SCHEMA);try(var rows=areaMappings.executeQuery()){rows.next();assertEquals(0,rows.getInt(1),"Every existing form must retain at least one mapped area after V38 backfill.");assertEquals(0,rows.getInt(2),"Every published form version must retain an area snapshot after V38 backfill.");assertEquals(1,rows.getInt(3),"Published version area mappings must be immutable.");}
            }
            try(var routing=connection.prepareStatement("SELECT (SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='defects_dtl' AND column_name='workflow_version_id'),(SELECT count(*) FROM information_schema.triggers WHERE trigger_schema=? AND event_object_table='defect_workflow_versions_dtl' AND trigger_name='defect_workflow_version_immutable'),(SELECT count(*) FROM pg_indexes WHERE schemaname=? AND indexname='area_maintenance_team_active_area_uq')")){
                for(int i=1;i<=3;i++)routing.setString(i,SCHEMA);
                try(var rows=routing.executeQuery()){rows.next();assertEquals(1,rows.getInt(1),"Defects must snapshot their tenant workflow version.");assertEquals(2,rows.getInt(2),"Workflow version rows must reject updates and deletes.");assertEquals(1,rows.getInt(3),"Only one active team mapping may exist per cabin area.");}
            }
            verifyAuditAppendOnly(connection);
            try (var auditColumns = connection.prepareStatement("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=? AND (table_name,column_name) IN (( 'fleets_mst','created_by'),('fleets_mst','created_at'),('fleets_mst','updated_by'),('fleets_mst','updated_at'),('operational_forms_dtl','created_by'),('operational_forms_dtl','created_at'),('operational_forms_dtl','updated_by'),('operational_forms_dtl','updated_at'),('operator_membership_teams_mst','created_by'),('operator_membership_teams_mst','created_at'),('operator_membership_teams_mst','updated_by'),('operator_membership_teams_mst','updated_at'))")) {
                auditColumns.setString(1, SCHEMA);
                try (var rows=auditColumns.executeQuery()) {
                    Set<String> found=new java.util.HashSet<>();
                    while(rows.next())found.add(rows.getString(1)+"."+rows.getString(2));
                    assertEquals(12,found.size(),"Mutable business tables must expose their audit actor and timestamp fields.");
                }
            }
            try (var immutableColumns = connection.prepareStatement("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name IN ('defect_events_dtl','operational_form_submissions_dtl','verifications_dtl','closure_approvals_dtl') AND column_name IN ('created_by','updated_by')")) {
                immutableColumns.setString(1, SCHEMA);
                try (var rows=immutableColumns.executeQuery()) { rows.next(); assertEquals(0,rows.getInt(1),"Append-only events and immutable decisions must retain their existing actor/time fields without duplicate mutable metadata."); }
            }
            try (var formAnswerColumns=connection.prepareStatement("SELECT count(*) FILTER (WHERE column_name='answers_json'),count(*) FILTER (WHERE column_name IN ('snapshot_defect_category','snapshot_component_id','snapshot_condition_id','snapshot_zone','snapshot_row_number','snapshot_seat_reference','snapshot_title','snapshot_description','snapshot_severity_id')) FROM information_schema.columns WHERE table_schema=? AND table_name='operational_form_submissions_dtl'")) {
                formAnswerColumns.setString(1,SCHEMA);
                try(var rows=formAnswerColumns.executeQuery()){rows.next();assertEquals(0,rows.getInt(1),"Form answers must not be stored in JSON/JSONB columns.");assertEquals(9,rows.getInt(2),"The immutable defect-report snapshot must use fixed typed relational columns.");}
            }
            try (var queryActive = connection.prepareStatement("SELECT table_name FROM information_schema.columns WHERE table_schema=? AND column_name='isactive' AND data_type='smallint' AND column_default LIKE '1%'")) {
                queryActive.setString(1, SCHEMA);
                try (var rows = queryActive.executeQuery()) {
                    Set<String> flagged = new java.util.HashSet<>();
                    while (rows.next()) flagged.add(rows.getString(1));
                    assertTrue(flagged.containsAll(SOFT_DELETE_TABLES), "Every mutable business table must have SMALLINT isactive DEFAULT 1.");
                }
            }
            try (var counts = connection.createStatement(); var rows = counts.executeQuery("SELECT (SELECT count(*) FROM " + SCHEMA + ".operators_mst), (SELECT count(*) FROM " + SCHEMA + ".navigation_menu_items_mst WHERE isactive=1)")) {
                rows.next();
                if (rows.getInt(1) > 0) assertTrue(rows.getInt(2) >= 5, "Existing organizations must receive the default navigation tree.");
            }
            try (var catalog = connection.createStatement(); var rows = catalog.executeQuery("SELECT (SELECT count(*) FROM " + SCHEMA + ".permissions_mst WHERE isactive=1 AND btrim(description)<>''), (SELECT count(DISTINCT permission_code) FROM " + SCHEMA + ".permissions_mst WHERE isactive=1), (SELECT count(*) FROM " + SCHEMA + ".defect_severities_mst WHERE severity_code IN ('LOW','MEDIUM','HIGH','CRITICAL') AND isactive=1), (SELECT count(*) FROM " + SCHEMA + ".defects_dtl WHERE severity_id IS NOT NULL AND severity IS NULL), (SELECT count(*) FROM " + SCHEMA + ".permissions_mst WHERE isactive=1 AND lower(description) LIKE '%allow%' AND lower(description) LIKE '%not%' AND btrim(module)<>'' )")) {
                rows.next();
                int supportedPermissions = com.ecabin.ledger.security.Permission.values().length;
                assertEquals(supportedPermissions, rows.getInt(1), "Each supported built-in permission must have a nonempty description.");
                assertEquals(supportedPermissions, rows.getInt(2), "Built-in permission codes must be unique and complete.");
                long organizations;
                try (var orgs = connection.createStatement(); var orgRows = orgs.executeQuery("SELECT count(*) FROM " + SCHEMA + ".operators_mst WHERE isactive=1")) { orgRows.next(); organizations = orgRows.getLong(1); }
                assertTrue(rows.getLong(3) >= organizations * 4, "Every active organization receives the four additive starter severity labels.");
                assertEquals(0, rows.getInt(4), "Linking legacy severity values must preserve their existing text snapshots.");
                assertEquals(supportedPermissions, rows.getInt(5), "Each permission description must state its allowance and a clear limitation, and include a functional module.");
            }
            try (var queryMenus = connection.prepareStatement("SELECT count(*) FROM " + SCHEMA + ".operators_mst WHERE id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1"); var organizations = queryMenus.executeQuery()) {
                organizations.next();
                if (organizations.getInt(1) > 0) try (var master = connection.createStatement(); var rows = master.executeQuery("SELECT count(*) FROM " + SCHEMA + ".navigation_menu_items_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND menu_key='menu-items' AND isactive=1 AND required_permissions='ADMINISTER_ORGANIZATION'")) {
                    rows.next(); assertEquals(1, rows.getInt(1), "The demo administrator must have the active menu-master submenu.");
                }
            }
        }

        if ("true".equalsIgnoreCase(System.getenv("ECABIN_SEED_DEMO"))) {
            String seedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
            var dataSource = new DriverManagerDataSource(seedUrl, username, password);
            var seed = new ResourceDatabasePopulator(new ClassPathResource("db/dev-seed.sql"));
            var jdbc = new JdbcTemplate(dataSource);
            List<String> originalAbcMembership = abcMembershipSnapshot(jdbc);
            List<String> originalGalleyPublishedVersions=jdbc.query("SELECT v.id::text||':'||v.fields_json::text FROM operational_forms_dtl f JOIN cabin_areas_mst a ON a.operator_id=f.operator_id AND a.id=f.area_id AND a.code='GALLEY' JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id AND v.status='PUBLISHED' WHERE f.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND f.purpose='DEFECT_REPORT' AND f.workflow_stage='INITIAL_REPORT' ORDER BY v.id",(rs,n)->rs.getString(1));
            seed.execute(dataSource);
            List<String> abcMembershipAfterFirstSeed = abcMembershipSnapshot(jdbc);
            seed.execute(dataSource); // The demo fixture must be safe to apply more than once.
            assertEquals(abcMembershipAfterFirstSeed, abcMembershipSnapshot(jdbc),
                "A repeated demo seed must not replace or modify the existing abc@gmail.com membership.");
            if (!originalAbcMembership.isEmpty()) assertEquals(originalAbcMembership, abcMembershipAfterFirstSeed,
                "Seeding must preserve the pre-existing abc@gmail.com identity, tenant, display name, role, and status.");
            assertEquals("Skyways Service (DEMO)", jdbc.queryForObject(
                "SELECT name FROM operators_mst WHERE id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", String.class));
            assertEquals(originalGalleyPublishedVersions,jdbc.query("SELECT v.id::text||':'||v.fields_json::text FROM operational_forms_dtl f JOIN cabin_areas_mst a ON a.operator_id=f.operator_id AND a.id=f.area_id AND a.code='GALLEY' JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id AND v.status='PUBLISHED' WHERE f.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND f.purpose='DEFECT_REPORT' AND f.workflow_stage='INITIAL_REPORT' AND v.created_by<>'local-demo-seed' ORDER BY v.id",(rs,n)->rs.getString(1)),"Demo initialization must preserve the immutable, administrator-created Galley published versions.");
            UUID demoOperator=UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
            UUID galleyArea=jdbc.queryForObject("SELECT id FROM cabin_areas_mst WHERE operator_id=? AND code='GALLEY' AND isactive=1",UUID.class,demoOperator);
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM component_conditions_mst cc JOIN cabin_components_mst c ON c.operator_id=cc.operator_id AND c.id=cc.component_id JOIN defect_conditions_mst d ON d.operator_id=cc.operator_id AND d.id=cc.condition_id WHERE cc.operator_id=? AND c.code='GALLEY_CART_LATCH' AND d.code='SURFACE_DAMAGE' AND cc.isactive=1 AND c.area_id=?",Integer.class,demoOperator,galleyArea),"The synthetic demo Galley component needs its explicitly named DEMO condition mapping.");
            var demoGalleyDrafts=jdbc.query("SELECT f.id,v.id AS version_id FROM operational_forms_dtl f JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id WHERE f.operator_id=? AND f.area_id=? AND f.purpose='DEFECT_REPORT' AND f.workflow_stage='INITIAL_REPORT' AND v.status='DRAFT' AND v.created_by='local-demo-seed' AND v.updated_by IS NULL AND v.fields_json @> '{\"fields\":[{\"binding\":\"AIRCRAFT_ID\",\"key\":\"aircraftId\",\"type\":\"AIRCRAFT_SELECT\",\"required\":true}]}'::jsonb",(rs,n)->new UUID[]{rs.getObject("id",UUID.class),rs.getObject("version_id",UUID.class)},demoOperator,galleyArea);
            var formService=new OperationalFormService(jdbc,new ObjectMapper());
            for(UUID[] draft:demoGalleyDrafts) formService.publish(demoOperator,"local-demo-seed",draft[0],draft[1]);
            var reviewerDrafts=jdbc.query("SELECT f.id,v.id AS version_id FROM operational_forms_dtl f JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id WHERE f.operator_id=? AND f.area_id=? AND f.form_key='LOCAL_DEMO_GALLEY_REVIEW_DECISION' AND f.created_by='local-demo-seed' AND f.purpose='REVIEW_DECISION' AND f.workflow_stage='REPORTED' AND v.status='DRAFT' AND v.created_by='local-demo-seed'",(rs,n)->new UUID[]{rs.getObject("id",UUID.class),rs.getObject("version_id",UUID.class)},demoOperator,galleyArea);
            for(UUID[] draft:reviewerDrafts) formService.publish(demoOperator,"local-demo-seed",draft[0],draft[1]);
            if(!reviewerDrafts.isEmpty()){
                var reviewerForms=formService.publishedForArea(demoOperator,galleyArea,com.ecabin.ledger.api.FormModels.FormPurpose.REVIEW_DECISION,com.ecabin.ledger.api.FormModels.WorkflowStage.REPORTED);
                assertTrue(reviewerForms.stream().anyMatch(form->form.formKey().equals("LOCAL_DEMO_GALLEY_REVIEW_DECISION")&&form.configuration().fields().size()==4),"The demo Reviewer form must publish through the same service validation and resolve by tenant, Galley, purpose and stage.");
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM operational_forms_dtl f JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id WHERE f.operator_id=? AND f.form_key='LOCAL_DEMO_GALLEY_REVIEW_DECISION'",Integer.class,demoOperator),"Repeated local initialization must not duplicate the Reviewer form or version.");
            }
            var closureDrafts=jdbc.query("SELECT f.id,v.id AS version_id FROM operational_forms_dtl f JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id WHERE f.operator_id=? AND f.area_id=? AND f.form_key='LOCAL_DEMO_GALLEY_CLOSURE_REVIEW' AND f.created_by='local-demo-seed' AND f.purpose='CLOSURE_APPROVAL' AND f.workflow_stage='APPROVAL_REQUIRED' AND v.status='DRAFT' AND v.created_by='local-demo-seed'",(rs,n)->new UUID[]{rs.getObject("id",UUID.class),rs.getObject("version_id",UUID.class)},demoOperator,galleyArea);
            boolean seededClosureForm=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM operational_forms_dtl WHERE operator_id=? AND area_id=? AND form_key='LOCAL_DEMO_GALLEY_CLOSURE_REVIEW' AND created_by='local-demo-seed' AND purpose='CLOSURE_APPROVAL')",Boolean.class,demoOperator,galleyArea));
            if(seededClosureForm){
                for(UUID[] draft:closureDrafts)formService.publish(demoOperator,"local-demo-seed",draft[0],draft[1]);
                var closureForms=formService.publishedForArea(demoOperator,galleyArea,com.ecabin.ledger.api.FormModels.FormPurpose.CLOSURE_APPROVAL,com.ecabin.ledger.api.FormModels.WorkflowStage.APPROVAL_REQUIRED);
                assertTrue(closureForms.stream().anyMatch(form->form.formKey().equals("LOCAL_DEMO_GALLEY_CLOSURE_REVIEW")&&form.configuration().fields().stream().filter(field->field.type()!=com.ecabin.ledger.api.FormModels.FieldType.SECTION).count()==2),"The local Galley Closure Review draft must publish using Form Master validation and remain visible in Form Master and area-purpose resolution.");
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM operational_forms_dtl f JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id WHERE f.operator_id=? AND f.form_key='LOCAL_DEMO_GALLEY_CLOSURE_REVIEW'",Integer.class,demoOperator),"Repeated local initialization must not duplicate the Galley Closure Review form or its initial version.");
            }else{
                assertTrue(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM operational_forms_dtl f JOIN operational_form_areas_mst fa ON fa.operator_id=f.operator_id AND fa.form_id=f.id WHERE f.operator_id=? AND fa.area_id=? AND f.purpose='CLOSURE_APPROVAL' AND f.isactive=1)",Boolean.class,demoOperator,galleyArea),"Preserve a manually configured closure form mapped to Galley; create the local demo draft only when no closure form already exists.");
            }
            var maintenanceDrafts=jdbc.query("SELECT f.id,v.id AS version_id FROM operational_forms_dtl f JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id WHERE f.operator_id=? AND f.form_key='LOCAL_DEMO_GALLEY_MAINTENANCE_ACTION' AND f.created_by='local-demo-seed' AND f.purpose='CORRECTIVE_ACTION' AND f.workflow_stage='IN_PROGRESS' AND v.status='DRAFT' AND v.created_by='local-demo-seed'",(rs,n)->new UUID[]{rs.getObject("id",UUID.class),rs.getObject("version_id",UUID.class)},demoOperator);
            for(UUID[] draft:maintenanceDrafts)formService.publish(demoOperator,"local-demo-seed",draft[0],draft[1]);
            var maintenanceForms=formService.publishedForArea(demoOperator,galleyArea,com.ecabin.ledger.api.FormModels.FormPurpose.CORRECTIVE_ACTION,com.ecabin.ledger.api.FormModels.WorkflowStage.IN_PROGRESS);
            assertTrue(maintenanceForms.stream().anyMatch(form->form.formKey().equals("LOCAL_DEMO_GALLEY_MAINTENANCE_ACTION")&&form.configuration().fields().size()==3),"The local Galley Maintenance Action form must publish through service validation with exactly its three configured business fields.");
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM operational_forms_dtl WHERE operator_id=? AND form_key='LOCAL_DEMO_GALLEY_MAINTENANCE_ACTION'",Integer.class,demoOperator),"Repeated local initialization must not duplicate a maintenance form.");
            List<com.ecabin.ledger.api.FormModels.FormForArea> galleyForms;
            boolean existingDraftNeedsAdministratorConfiguration=false;
            try{galleyForms=formService.publishedForArea(demoOperator,galleyArea,com.ecabin.ledger.api.FormModels.FormPurpose.DEFECT_REPORT,com.ecabin.ledger.api.FormModels.WorkflowStage.INITIAL_REPORT);}
            catch(org.springframework.web.server.ResponseStatusException configurationRequired){
                assertTrue(configurationRequired.getReason().contains("Aircraft Registration selector"),"Invalid legacy reports must fail resolution with actionable Form Master guidance.");
                assertTrue(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM operational_forms_dtl f JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id WHERE f.operator_id=? AND f.area_id=? AND f.purpose='DEFECT_REPORT' AND f.workflow_stage='INITIAL_REPORT' AND v.status='DRAFT')",Boolean.class,demoOperator,galleyArea),"A pre-existing administrator draft is preserved for an authorized administrator to finish in Form Master.");
                existingDraftNeedsAdministratorConfiguration=true;galleyForms=List.of();
            }
            if(!existingDraftNeedsAdministratorConfiguration){assertTrue(!galleyForms.isEmpty(),"A published, tenant-scoped Galley defect-report form must resolve through the reporting service.");assertTrue(galleyForms.stream().anyMatch(form->form.configuration().fields().stream().anyMatch(field->field.binding()==com.ecabin.ledger.api.FormModels.DefectFieldBinding.AIRCRAFT_ID&&field.type()==com.ecabin.ledger.api.FormModels.FieldType.AIRCRAFT_SELECT&&field.required())),"The resolved local Galley form must expose the required system aircraft selector.");}
            var apiToken=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("smoke-test-token").header("alg","HS256")
                .subject("auditor.test@ecabin.local").issuedAt(java.time.Instant.now()).expiresAt(java.time.Instant.now().plusSeconds(60))
                .claim("operator_id",demoOperator.toString()).build();
            var operationalApi=new com.ecabin.ledger.api.OperationalFormsController(formService,
                new com.ecabin.ledger.security.CurrentUserResolver(jdbc),new com.ecabin.ledger.security.PermissionService(jdbc),
                new DefectService(jdbc),new com.ecabin.ledger.service.FormFieldBindingCatalog());
            if(existingDraftNeedsAdministratorConfiguration) {
                var apiConfigurationRequired=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->operationalApi.forArea(
                    new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(apiToken),galleyArea,
                    com.ecabin.ledger.api.FormModels.FormPurpose.DEFECT_REPORT,com.ecabin.ledger.api.FormModels.WorkflowStage.INITIAL_REPORT));
                assertTrue(apiConfigurationRequired.getReason().contains("Aircraft Registration selector"),
                    "The authenticated reporting API must surface the same actionable configuration error as the service.");
            } else {
                var apiResolvedForms=operationalApi.forArea(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(apiToken),
                    galleyArea,com.ecabin.ledger.api.FormModels.FormPurpose.DEFECT_REPORT,com.ecabin.ledger.api.FormModels.WorkflowStage.INITIAL_REPORT);
                System.out.println("Eligible Galley reporting forms: "+apiResolvedForms.stream().map(form->form.formKey()+"/v"+form.versionNumber()+" fields="+form.configuration().fields().stream().map(field->field.label()+"="+field.binding()).toList()).toList());
                assertEquals(galleyForms.stream().map(form->form.id()+":"+form.versionId()).toList(),
                    apiResolvedForms.stream().map(form->form.id()+":"+form.versionId()).toList(),
                    "The authenticated GET /api/v1/areas/{areaId}/forms controller must return the same eligible published Galley forms as the tenant-scoped resolution service.");
            }
            var seededGalley=galleyForms.stream().filter(form->"LOCAL_DEMO_GALLEY_AREA".equals(form.formKey())).findFirst();
            if(seededGalley.isPresent()) {
                var definition=seededGalley.get();var fields=definition.configuration().fields();
                assertEquals(7,fields.size(),"The local Galley demo definition should contain the aircraft selector and six configured business/evidence fields.");
                assertEquals(List.of(com.ecabin.ledger.api.FormModels.DefectFieldBinding.AIRCRAFT_ID,com.ecabin.ledger.api.FormModels.DefectFieldBinding.ZONE,com.ecabin.ledger.api.FormModels.DefectFieldBinding.COMPONENT_ID,com.ecabin.ledger.api.FormModels.DefectFieldBinding.CONDITION_ID,com.ecabin.ledger.api.FormModels.DefectFieldBinding.SEVERITY,com.ecabin.ledger.api.FormModels.DefectFieldBinding.REMARKS,com.ecabin.ledger.api.FormModels.DefectFieldBinding.EVIDENCE_ATTACHMENTS),fields.stream().map(com.ecabin.ledger.api.FormModels.FieldDefinition::binding).toList());
                assertTrue(fields.get(0).required()&&"aircraftId".equals(fields.get(0).key())&&fields.get(0).type()==com.ecabin.ledger.api.FormModels.FieldType.AIRCRAFT_SELECT,"The aircraft identity selector must be required, system-bound, and first in the published design.");
                assertTrue(fields.get(6).required()&&fields.get(6).fileRules().minCount()==1,"The demo image evidence field must remain last, required, and use the configured attachment rules.");
                var aircraftOptions=formService.reportingAircraft(demoOperator,"DEMO-A320-01",0,20);
                assertTrue(aircraftOptions.items().stream().anyMatch(option->"DEMO-A320-01".equals(option.tailNumber())&&"DEMO-32001".equals(option.msnNumber())),"The tenant-scoped aircraft search must resolve the seeded registration and MSN from aircraft master.");
                assertEquals(1,formService.options(demoOperator,definition.id(),definition.versionId(),"component_ref",galleyArea,null).stream().filter(option->"GALLEY_CART_LATCH".equals(option.code())).count());
                UUID component=jdbc.queryForObject("SELECT id FROM cabin_components_mst WHERE operator_id=? AND code='GALLEY_CART_LATCH' AND area_id=? AND isactive=1",UUID.class,demoOperator,galleyArea);
                assertEquals(1,formService.options(demoOperator,definition.id(),definition.versionId(),"condition_ref",galleyArea,component).stream().filter(option->"SURFACE_DAMAGE".equals(option.code())).count());
            }
            Integer demoFormCount=jdbc.queryForObject("SELECT count(*) FROM operational_forms_dtl f JOIN operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id AND v.id=f.published_version_id AND v.status='PUBLISHED' WHERE f.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND f.form_key LIKE 'DEMO_REPORT_%' AND f.isactive=1",Integer.class);
            assertTrue(demoFormCount>0,"The local demo must provide a published, relationally mapped defect-report form where explicit component-condition mappings exist.");
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM aircraft_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM defects_dtl WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class) >= 4,
                "The idempotent demo seed should retain its four sample defects without rejecting additional tenant records.");
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM defect_events_dtl WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND event_type='DEMO_SEEDED'", Integer.class) >= 4,
                "The idempotent demo seed should retain its four sample events without rejecting additional tenant records.");
            assertEquals(1, abcMembershipAfterFirstSeed.size(), "The existing local demo identity must remain present exactly once.");
            assertEquals("ADMIN", jdbc.queryForObject("SELECT role FROM operator_memberships_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND external_user_id='admin.test@ecabin.local'", String.class));
            assertEquals("AUDITOR", jdbc.queryForObject("SELECT role FROM operator_memberships_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND external_user_id='auditor.test@ecabin.local'", String.class));
            assertEquals("REVIEWER", jdbc.queryForObject("SELECT role FROM operator_memberships_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND external_user_id='reviewer.test@ecabin.local'", String.class));
            assertEquals("MAINTENANCE_TECHNICIAN", jdbc.queryForObject("SELECT role FROM operator_memberships_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND external_user_id='maintenance.test@ecabin.local'", String.class));
            var userResolver = new com.ecabin.ledger.security.CurrentUserResolver(jdbc);
            assertEquals("ADMIN", resolveLocalUser(userResolver, "admin.test@ecabin.local").role());
            assertEquals("AUDITOR", resolveLocalUser(userResolver, "auditor.test@ecabin.local").role());
            assertEquals("REVIEWER", resolveLocalUser(userResolver, "reviewer.test@ecabin.local").role());
            assertEquals("MAINTENANCE_TECHNICIAN", resolveLocalUser(userResolver, "maintenance.test@ecabin.local").role());
            var permissions = new com.ecabin.ledger.security.PermissionService(jdbc);
            assertTrue(permissions.permissionsFor(demoOperator, "ADMIN").containsAll(Set.of(
                com.ecabin.ledger.security.Permission.REPORT_VIEW,
                com.ecabin.ledger.security.Permission.REPORT_EXECUTE,
                com.ecabin.ledger.security.Permission.REPORT_EXPORT)),
                "The organization ADMIN role receives report application permissions after V59.");
            ConfigurationService service = new ConfigurationService(jdbc, new ObjectMapper());
            assertEquals(Set.of(com.ecabin.ledger.security.Permission.values()).stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                new java.util.HashSet<>(jdbc.queryForList("SELECT permission_code FROM permissions_mst WHERE isactive=1", String.class)), "The read-only database catalog must match the server-authoritative Permission enum.");
            assertEquals(com.ecabin.ledger.security.Permission.values().length,
                service.table(demoOperator, "permissions", 0, 100, "", "ACTIVE", null, null, null, "code", "ASC").totalElements());
            assertTrue(service.table(demoOperator, "role-permissions", 0, 100, "", "ACTIVE", null, null, null, "name", "ASC").totalElements() >= 6);
            assertEquals(7, service.table(demoOperator, "severities", 0, 100, "", "ACTIVE", null, null, null, "sortOrder", "ASC").totalElements());
            assertEquals("Low", service.list(demoOperator, "severities", false).get(0).get("name"));
            assertTrue(permissions.permissionsFor(demoOperator, "AUDITOR").containsAll(Set.of(
                com.ecabin.ledger.security.Permission.VIEW, com.ecabin.ledger.security.Permission.REPORT)),
                "The Auditor role must retain its baseline permissions while preserving any existing tenant-configured grants.");
            assertTrue(permissions.permissionsFor(demoOperator, "REVIEWER").containsAll(Set.of(
                com.ecabin.ledger.security.Permission.VIEW, com.ecabin.ledger.security.Permission.REVIEW)),
                "The Reviewer role must retain its baseline permissions while preserving any existing tenant-configured grants.");
            assertTrue(permissions.permissionsFor(demoOperator, "MAINTENANCE_TECHNICIAN").contains(com.ecabin.ledger.security.Permission.PERFORM_ACTION));
            var reviewerNavigation = service.navigation(demoOperator, permissions.permissionsFor(demoOperator, "REVIEWER"));
            var auditorNavigation = service.navigation(demoOperator, permissions.permissionsFor(demoOperator, "AUDITOR"));
            var maintenanceNavigation = service.navigation(demoOperator, permissions.permissionsFor(demoOperator, "MAINTENANCE_TECHNICIAN"));
            var adminNavigation = service.navigation(demoOperator, permissions.permissionsFor(demoOperator, "ADMIN"));
            Set<String> activeOperationsEntries = new java.util.HashSet<>(jdbc.queryForList("SELECT child.menu_key FROM navigation_menu_items_mst child JOIN navigation_menu_items_mst parent ON parent.operator_id=child.operator_id AND parent.id=child.parent_id WHERE child.operator_id=? AND parent.menu_key='operations' AND parent.parent_id IS NULL AND child.isactive=1", String.class, demoOperator));
            assertEquals(Set.of("defects", "report-defect"), activeOperationsEntries, "Operations should expose only Defect Register and Report Defect.");
            Set<String> reviewerMenu = flattenNavigation(reviewerNavigation);
            assertTrue(reviewerMenu.contains("defects"), "The Reviewer account should receive the shared Defect Register through its VIEW permission.");
            assertTrue(!reviewerMenu.contains("review-queue"), "The separate Reviewer queue is no longer an Operations menu entry.");
            assertTrue(!flattenNavigation(reviewerNavigation).contains("roles") && !flattenNavigation(reviewerNavigation).contains("menu-items"), "Reviewer must not receive organization administration screens.");
            assertEquals(permissions.permissionsFor(demoOperator, "AUDITOR").contains(com.ecabin.ledger.security.Permission.AUDIT),
                flattenNavigation(auditorNavigation).contains("audit"),
                "The Auditor audit menu must match its configured AUDIT permission.");
            assertTrue(!flattenNavigation(auditorNavigation).contains("review-queue"), "Auditor must not receive Reviewer queue access without REVIEW.");
            assertTrue(!flattenNavigation(maintenanceNavigation).contains("audit"), "Maintenance must not receive the audit menu without AUDIT.");
            assertTrue(flattenNavigation(adminNavigation).contains("roles") && flattenNavigation(adminNavigation).contains("menu-items"), "The separate tenant ADMIN account should receive organization administration menus.");
            assertTrue(flattenNavigation(adminNavigation).containsAll(Set.of("severities", "permissions", "role-permissions")), "The administrator navigation should expose severity and permission administration views.");
            assertTrue(flattenNavigation(adminNavigation).contains("reports"), "Organization administrators can open the Reports shell without report-data permissions.");
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operator_membership_teams_mst omt JOIN maintenance_teams_mst t ON t.operator_id=omt.operator_id AND t.name=omt.team_name WHERE omt.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND omt.external_user_id='reviewer.test@ecabin.local' AND omt.team_name='Cabin Review' AND omt.isactive=1 AND t.team_type='REVIEWER' AND t.isactive=1", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operator_membership_teams_mst omt JOIN maintenance_teams_mst t ON t.operator_id=omt.operator_id AND t.name=omt.team_name WHERE omt.operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND omt.external_user_id='maintenance.test@ecabin.local' AND omt.team_name='Cabin Maintenance' AND omt.isactive=1 AND t.team_type='MAINTENANCE' AND t.isactive=1", Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM maintenance_teams_mst WHERE operator_id=? AND name='Cabin Review' AND team_type='REVIEWER' AND isactive=1",Integer.class,demoOperator));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM maintenance_teams_mst WHERE operator_id=? AND name='Cabin Maintenance' AND team_type='MAINTENANCE' AND isactive=1",Integer.class,demoOperator));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM operators_mst o JOIN maintenance_teams_mst t ON t.operator_id=o.id AND t.id=o.default_reviewer_team_id WHERE o.id=? AND t.name='Cabin Review' AND t.team_type='REVIEWER' AND t.isactive=1",Integer.class,demoOperator));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operator_memberships_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND external_user_id IN ('admin.test@ecabin.local','auditor.test@ecabin.local','reviewer.test@ecabin.local','maintenance.test@ecabin.local') AND role='SUPERADMIN'", Integer.class));
            assertEquals(11, jdbc.queryForObject("SELECT count(*) FROM defect_categories_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM cabin_areas_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            assertEquals(12, jdbc.queryForObject("SELECT count(*) FROM cabin_components_mst WHERE operator_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND isactive=1", Integer.class));
            assertEquals("Defect Summary", jdbc.queryForObject("SELECT label FROM navigation_menu_items_mst WHERE operator_id=? AND menu_key='home'", String.class, demoOperator));
            for (String resource : List.of("organizations", "fleets", "aircraft", "areas", "defect-categories", "defect-conditions", "components", "maintenance-teams", "users", "menu-items", "roles", "severities", "permissions", "role-permissions")) {
                ConfigurationService.TablePage page = service.table(demoOperator, resource, 0, 25, "", "ALL", null, null, null, null, null);
                assertTrue(page.totalPages() >= 1, "Every administration master should return consistent paging metadata: " + resource);
                ByteArrayOutputStream workbook = new ByteArrayOutputStream();
                service.exportExcel(demoOperator, resource, "", "ACTIVE", null, null, null, null, "ASC", null, workbook);
                assertTrue(workbook.size() > 100, "Each administration master should export an XLSX workbook: " + resource);
                assertEquals(0x50, Byte.toUnsignedInt(workbook.toByteArray()[0]), "An XLSX export must begin with ZIP signature: " + resource);
            }
            assertTrue(new com.ecabin.ledger.security.PermissionService(jdbc).permissionsFor(demoOperator, "INSPECTOR").contains(com.ecabin.ledger.security.Permission.INSPECT), "Permissions must resolve from the tenant role master.");
            var fleetStatus = new com.ecabin.ledger.service.DefectService(jdbc).fleetStatus(demoOperator, 0, 1, "DEMO-32001", "msnNumber", "ASC");
            assertEquals(1, fleetStatus.totalElements());
            assertEquals("DEMO-A320-01", fleetStatus.items().get(0).tailNumber());
            assertTrue(fleetStatus.items().get(0).openDefects() >= 2,
                "The demo aircraft retains its two seeded open defects, while any additional tenant defects remain included.");
            ByteArrayOutputStream fleetWorkbook = new ByteArrayOutputStream();
            new com.ecabin.ledger.service.DefectService(jdbc).exportFleetStatus(demoOperator, "", "tailNumber", "ASC", null, fleetWorkbook);
            assertEquals(0x50, Byte.toUnsignedInt(fleetWorkbook.toByteArray()[0]), "Fleet Status should export as XLSX.");
            ConfigurationService.TablePage firstAircraftPage = service.table(demoOperator, "aircraft", 0, 1, "", "ACTIVE", null, null, null, "tailNumber", "ASC");
            ByteArrayOutputStream allAircraft = new ByteArrayOutputStream();
            service.exportExcel(demoOperator, "aircraft", "", "ACTIVE", null, null, null, "tailNumber", "ASC", List.of("tailNumber","aircraftType"), allAircraft);
            String sheetXml;
            try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(allAircraft.toByteArray()))) {
                java.util.zip.ZipEntry entry; String contents = "";
                while ((entry = zip.getNextEntry()) != null) if (entry.getName().equals("xl/worksheets/sheet1.xml")) contents = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                sheetXml = contents;
            }
            long exportedRows = java.util.regex.Pattern.compile("<row ").matcher(sheetXml).results().count() - 1;
            assertEquals(firstAircraftPage.totalElements(), exportedRows, "Export must include all filtered records, independent of the current page size.");
            assertTrue(sheetXml.indexOf("Registration") < sheetXml.indexOf("Aircraft type"), "Visible export headers must retain the displayed order.");
            assertTrue(!sheetXml.contains("MSN Number")&&!sheetXml.contains("Open Defects"), "Hidden and unrelated fields must not appear in the requested aircraft export.");
            assertEquals(1, service.table(demoOperator, "aircraft", 0, 25, "DEMO-A320-01", "ACTIVE", null, null, "A320-200", "tailNumber", "DESC").totalElements(), "Search and aircraft-type filters must be applied by PostgreSQL.");
        }
    }

    private static List<String> abcMembershipSnapshot(JdbcTemplate jdbc) {
        return jdbc.query("SELECT operator_id::text,external_user_id,display_name,role,isactive::text FROM operator_memberships_mst WHERE external_user_id='abc@gmail.com' ORDER BY operator_id",
            (rs, row) -> String.join("\u001f", rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
    }

    private static Set<String> flattenNavigation(List<com.ecabin.ledger.api.ApiModels.NavigationItem> items) {
        Set<String> keys = new java.util.HashSet<>();
        for (var item : items) {
            keys.add(item.key());
            keys.addAll(flattenNavigation(item.children()));
        }
        return keys;
    }

    private static com.ecabin.ledger.security.AuthenticatedUser resolveLocalUser(
            com.ecabin.ledger.security.CurrentUserResolver users, String email) {
        var now = java.time.Instant.now();
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-token")
            .header("alg", "HS256").subject(email).issuedAt(now).expiresAt(now.plusSeconds(60))
            .claim("operator_id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").build();
        return users.require(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
    }

    private static void verifySchemaIsSafeToUse(String url, String username, String password) throws Exception {
        try (var connection = DriverManager.getConnection(url, username, password);
             var target = connection.createStatement();
             var targetRow = target.executeQuery("SELECT current_database(), current_schema()")) {
            assertTrue(targetRow.next(), "The configured PostgreSQL target must be available before migration.");
            String database = targetRow.getString(1);
            String schema = targetRow.getString(2);
            assertEquals(SCHEMA, schema, "The configured JDBC search path must point at the project schema before migration.");
            System.out.printf("PostgreSQL verification target: database=%s schema=%s%n", database, schema);
        }
        try (var connection = DriverManager.getConnection(url, username, password);
             var query = connection.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=?")) {
            query.setString(1, SCHEMA);
            try (var rows = query.executeQuery()) {
                Set<String> existing = new java.util.HashSet<>();
                while (rows.next()) existing.add(rows.getString(1));
                if (!existing.isEmpty()) {
                    assertTrue(existing.contains("flyway_schema_history"),
                        "Refusing to migrate a non-empty schema without this application's Flyway history.");
                }
            }
        }
    }

    private static int appliedMigrationCount(java.sql.Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + SCHEMA + ".flyway_schema_history WHERE success=true AND version IS NOT NULL")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void verifyAuditAppendOnly(java.sql.Connection connection) throws Exception {
        connection.setSchema(SCHEMA);
        boolean autoCommit=connection.getAutoCommit();
        connection.setAutoCommit(false);
        UUID operator=UUID.randomUUID(), event=UUID.randomUUID();
        try {
            try (var create=connection.prepareStatement("INSERT INTO "+SCHEMA+".operators_mst(id,name) VALUES(?,?)")) {
                create.setObject(1,operator); create.setString(2,"Migration smoke test tenant"); create.executeUpdate();
            }
            try (var insert=connection.prepareStatement("INSERT INTO "+SCHEMA+".defect_events_dtl(id,operator_id,event_type,note,actor_id,entity_id) VALUES(?,?,'MIGRATION_SMOKE','append-only check','smoke-test',?)")) {
                insert.setObject(1,event); insert.setObject(2,operator); insert.setObject(3,UUID.randomUUID()); insert.executeUpdate();
            }
            var updateSavepoint=connection.setSavepoint();
            try (var update=connection.prepareStatement("UPDATE "+SCHEMA+".defect_events_dtl SET note='mutation rejected' WHERE id=?")) {
                update.setObject(1,event); assertThrows(SQLException.class,update::executeUpdate,"Audit updates must be rejected after table rename.");
            } finally { connection.rollback(updateSavepoint); }
            var deleteSavepoint=connection.setSavepoint();
            try (var delete=connection.prepareStatement("DELETE FROM "+SCHEMA+".defect_events_dtl WHERE id=?")) {
                delete.setObject(1,event); assertThrows(SQLException.class,delete::executeUpdate,"Audit deletes must be rejected after table rename.");
            } finally { connection.rollback(deleteSavepoint); }
        } finally {
            connection.rollback();
            connection.setAutoCommit(autoCommit);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required DB verification setting is missing: " + name);
        return value;
    }
}
