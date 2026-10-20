UPDATE permissions_mst
SET description = 'Allows exporting a report when application and per-report view, execution, and export grants are present. Does not allow access to reports without those explicit grants.'
WHERE permission_code = 'REPORT_EXPORT'
  AND description IS DISTINCT FROM 'Allows exporting a report when application and per-report view, execution, and export grants are present. Does not allow access to reports without those explicit grants.';
