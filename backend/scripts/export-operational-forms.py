#!/usr/bin/env python3
"""Export exactly four tenant Operational Forms for review before a Flyway transfer.

Uses psql's standard PG* environment variables (PGHOST, PGPORT, PGDATABASE,
PGUSER, PGPASSWORD, PGSSLMODE). No credentials are accepted on the command line
or written to the export. Generated database IDs are intentionally omitted.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="Path for deterministic JSON export")
    args = parser.parse_args()

    operator_id = os.environ.get("ECABIN_FORM_EXPORT_OPERATOR_ID", "").strip()
    schema = os.environ.get("DB_SCHEMA", "ecabin_ledger").strip()
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", operator_id):
        parser.error("Set ECABIN_FORM_EXPORT_OPERATOR_ID to the source tenant UUID.")
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,62}", schema):
        parser.error("DB_SCHEMA must be a simple PostgreSQL identifier.")
    for variable in ("PGHOST", "PGPORT", "PGDATABASE", "PGUSER"):
        if not os.environ.get(variable):
            parser.error(f"Set {variable} using the source PostgreSQL connection settings.")

    # Identifiers are constrained above; UUID is passed as a SQL literal only after
    # strict hexadecimal/hyphen validation. No source-generated IDs enter the file.
    q = f'"{schema}"'
    sql = f"""
WITH selected_tenant AS (
  SELECT id, name FROM {q}.operators_mst WHERE id = '{operator_id}'::uuid AND isactive = 1
), selected_forms AS (
  SELECT f.*, a.code AS area_code, a.name AS area_name,
         pv.version_number AS published_version_number
  FROM {q}.operational_forms_dtl f
  JOIN selected_tenant t ON t.id = f.operator_id
  JOIN {q}.cabin_areas_mst a ON a.operator_id = f.operator_id AND a.id = f.area_id
  LEFT JOIN {q}.operational_form_versions_dtl pv
    ON pv.operator_id = f.operator_id AND pv.form_id = f.id AND pv.id = f.published_version_id
  WHERE f.isactive = 1
), form_count AS (SELECT count(*) AS total FROM selected_forms),
inactive_forms AS (
  SELECT COALESCE(jsonb_agg(f.form_key ORDER BY f.form_key), '[]'::jsonb) AS form_keys
  FROM {q}.operational_forms_dtl f JOIN selected_tenant t ON t.id=f.operator_id WHERE f.isactive=2
),
payload AS (
  SELECT jsonb_build_object(
    'format', 'ecabin-operational-forms-export-v1',
    'sourceTenantName', (SELECT name FROM selected_tenant),
    'sourceFormCount', (SELECT total FROM form_count),
    'excludedInactiveFormKeys', (SELECT form_keys FROM inactive_forms),
    'forms', COALESCE((
      SELECT jsonb_agg(jsonb_build_object(
        'formKey', f.form_key, 'name', f.name,
        'area', jsonb_build_object('code', f.area_code, 'name', f.area_name),
        'purpose', f.purpose, 'workflowStage', f.workflow_stage, 'isActive', f.isactive,
        'publishedVersionNumber', f.published_version_number,
        'versions', COALESCE((
          SELECT jsonb_agg(jsonb_build_object(
            'versionNumber', v.version_number, 'status', v.status,
            'fields', v.fields_json->'fields', 'createdBy', v.created_by,
            'createdAt', v.created_at, 'publishedBy', v.published_by, 'publishedAt', v.published_at
          ) ORDER BY v.version_number)
          FROM {q}.operational_form_versions_dtl v
          WHERE v.operator_id=f.operator_id AND v.form_id=f.id
        ), '[]'::jsonb)
      ) ORDER BY f.form_key)
      FROM selected_forms f
    ), '[]'::jsonb),
    'referenceDependencies', jsonb_build_object(
      'areaCodes', COALESCE((SELECT jsonb_agg(DISTINCT f.area_code ORDER BY f.area_code) FROM selected_forms f), '[]'::jsonb),
      'areas', COALESCE((
        SELECT jsonb_agg(jsonb_build_object('code', a.code, 'name', a.name, 'isActive', a.isactive) ORDER BY a.code)
        FROM {q}.cabin_areas_mst a WHERE a.operator_id='{operator_id}'::uuid AND a.isactive=1
          AND (EXISTS (SELECT 1 FROM selected_forms f WHERE f.area_code=a.code) OR
               EXISTS (SELECT 1 FROM selected_forms f JOIN {q}.operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id
                       CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') field(value)
                       WHERE field.value->>'optionSource'='CABIN_AREAS'))
      ), '[]'::jsonb),
      'optionSources', COALESCE((
        SELECT jsonb_agg(DISTINCT field.value->>'optionSource' ORDER BY field.value->>'optionSource')
        FROM selected_forms f
        JOIN {q}.operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id
        CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') field(value)
        WHERE field.value ? 'optionSource'
      ), '[]'::jsonb),
      'components', COALESCE((
        SELECT jsonb_agg(jsonb_build_object('code', c.code, 'name', c.name, 'areaCode', a.code, 'isActive', c.isactive) ORDER BY c.code)
        FROM {q}.cabin_components_mst c JOIN {q}.cabin_areas_mst a ON a.operator_id=c.operator_id AND a.id=c.area_id
        WHERE c.operator_id='{operator_id}'::uuid AND c.isactive=1
          AND EXISTS (SELECT 1 FROM selected_forms f JOIN {q}.operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id
                      CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') field(value)
                      WHERE field.value->>'optionSource'='COMPONENTS')
      ), '[]'::jsonb),
      'conditions', COALESCE((
        SELECT jsonb_agg(jsonb_build_object('code', d.code, 'name', d.name, 'isActive', d.isactive) ORDER BY d.code)
        FROM {q}.defect_conditions_mst d WHERE d.operator_id='{operator_id}'::uuid AND d.isactive=1
          AND EXISTS (SELECT 1 FROM selected_forms f JOIN {q}.operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id
                      CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') field(value)
                      WHERE field.value->>'optionSource'='DEFECT_CONDITIONS')
      ), '[]'::jsonb),
      'componentConditionMappings', COALESCE((
        SELECT jsonb_agg(jsonb_build_object('componentCode', c.code, 'conditionCode', d.code) ORDER BY c.code, d.code)
        FROM {q}.component_conditions_mst m
        JOIN {q}.cabin_components_mst c ON c.operator_id=m.operator_id AND c.id=m.component_id
        JOIN {q}.defect_conditions_mst d ON d.operator_id=m.operator_id AND d.id=m.condition_id
        WHERE m.operator_id='{operator_id}'::uuid AND m.isactive=1 AND c.isactive=1 AND d.isactive=1
          AND EXISTS (SELECT 1 FROM selected_forms f JOIN {q}.operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id
                      CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') field(value)
                      WHERE field.value->>'optionSource'='DEFECT_CONDITIONS')
      ), '[]'::jsonb),
      'categories', COALESCE((
        SELECT jsonb_agg(jsonb_build_object('code', c.code, 'name', c.name, 'isActive', c.isactive) ORDER BY c.code)
        FROM {q}.defect_categories_mst c WHERE c.operator_id='{operator_id}'::uuid AND c.isactive=1
          AND EXISTS (SELECT 1 FROM selected_forms f JOIN {q}.operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id
                      CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') field(value)
                      WHERE field.value->>'optionSource'='DEFECT_CATEGORIES')
      ), '[]'::jsonb),
      'severities', COALESCE((
        SELECT jsonb_agg(jsonb_build_object('code', s.severity_code, 'name', s.severity_name, 'isActive', s.isactive) ORDER BY s.severity_code)
        FROM {q}.defect_severities_mst s WHERE s.operator_id='{operator_id}'::uuid AND s.isactive=1
          AND EXISTS (SELECT 1 FROM selected_forms f JOIN {q}.operational_form_versions_dtl v ON v.operator_id=f.operator_id AND v.form_id=f.id
                      CROSS JOIN LATERAL jsonb_array_elements(v.fields_json->'fields') field(value)
                      WHERE field.value->>'optionSource' IN ('DEFECT_SEVERITIES','GALLEY_SEVERITIES'))
      ), '[]'::jsonb)
    )
  ) AS document
)
SELECT CASE WHEN (SELECT total FROM form_count) <> 4 THEN
  jsonb_build_object('error', format('Expected exactly 4 source forms for tenant %s; found %s. No export written.', '{operator_id}', (SELECT total FROM form_count)))
ELSE (SELECT document FROM payload) END::text;
"""
    result = subprocess.run(
        ["psql", "--no-psqlrc", "--set", "ON_ERROR_STOP=1", "--tuples-only", "--no-align", "--command", sql],
        check=False, capture_output=True, text=True, env=os.environ.copy(),
    )
    if result.returncode:
        print("PostgreSQL export failed. Check the configured PG* environment and schema; no output was written.", file=sys.stderr)
        if result.stderr.strip():
            print(result.stderr.strip(), file=sys.stderr)
        return result.returncode
    try:
        document = json.loads(result.stdout.strip())
    except json.JSONDecodeError:
        print("psql did not return a single valid JSON document; no output was written.", file=sys.stderr)
        return 2
    if "error" in document:
        print(document["error"], file=sys.stderr)
        return 3
    if document.get("sourceFormCount") != 4 or len(document.get("forms", [])) != 4:
        print("Export validation failed: expected exactly four forms; no output was written.", file=sys.stderr)
        return 3
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Exported four forms to {args.output}. Review it; it contains no generated database IDs or credentials.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
