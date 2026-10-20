-- Register the read-only, tenant-scoped defect visibility capability.
-- Role grants remain administrator-managed; this migration does not assign it.
INSERT INTO permissions_mst(permission_code, permission_name, description, module, is_system_permission)
VALUES (
  'DEFECT_VIEW_ALL',
  'View all tenant defects',
  'Allows viewing all defects within the user''s authorized tenant scope, regardless of creator or assigned team. Does not grant modification or workflow permissions.',
  'Defects',
  true
)
ON CONFLICT (permission_code) DO NOTHING;
