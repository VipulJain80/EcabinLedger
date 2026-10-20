-- Reviewer approval now routes Galley defects directly to Maintenance.
-- Backfill only still-pending legacy approvals that have no inspection history.
-- The selected team is read from the immutable REVIEW_APPROVED event; tenant,
-- area, active team membership, and an existing action are verified before use.
DO $$
DECLARE
    item record;
    team_id uuid;
    team_name varchar(120);
    action_id uuid;
    action_count integer;
    action_team varchar(120);
    action_status varchar(20);
BEGIN
    FOR item IN
        SELECT d.operator_id,
               d.id AS defect_id,
               d.reference,
               d.title,
               d.severity,
               d.status AS previous_status,
               approval.id AS approval_event_id,
               approval.actor_id AS reviewer_id,
               approval.new_state->>'maintenanceTeamId' AS maintenance_team_id
        FROM defects_dtl d
        JOIN cabin_areas_mst area
          ON area.operator_id=d.operator_id AND area.id=d.area_id AND area.code='GALLEY'
        JOIN LATERAL (
            SELECT ev.id, ev.actor_id, ev.new_state
            FROM defect_events_dtl ev
            WHERE ev.operator_id=d.operator_id
              AND ev.defect_id=d.id
              AND ev.event_type='REVIEW_APPROVED'
            ORDER BY ev.occurred_at DESC, ev.id DESC
            LIMIT 1
        ) approval ON true
        WHERE d.isactive=1
          AND d.status='INSPECTION_REQUIRED'
          AND NOT EXISTS (
              SELECT 1 FROM inspections_dtl insp
              WHERE insp.operator_id=d.operator_id AND insp.defect_id=d.id
          )
    LOOP
        IF item.maintenance_team_id IS NULL OR btrim(item.maintenance_team_id)=''
           OR item.maintenance_team_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
            RAISE EXCEPTION 'Cannot route Galley defect %: its immutable Reviewer approval has no valid Maintenance team ID.', item.reference;
        END IF;
        team_id := item.maintenance_team_id::uuid;

        SELECT mt.name INTO team_name
        FROM maintenance_teams_mst mt
        WHERE mt.operator_id=item.operator_id
          AND mt.id=team_id
          AND mt.team_type='MAINTENANCE'
          AND mt.isactive=1
          AND EXISTS (
              SELECT 1
              FROM operator_membership_teams_mst member_team
              JOIN operator_memberships_mst membership
                ON membership.operator_id=member_team.operator_id
               AND membership.external_user_id=member_team.external_user_id
               AND membership.isactive=1
              JOIN operator_roles_mst r
                ON r.operator_id=membership.operator_id
               AND r.role_key=membership.role
               AND r.isactive=1
              WHERE member_team.operator_id=mt.operator_id
                AND member_team.team_name=mt.name
                AND member_team.isactive=1
                AND ('|'||r.permissions||'|') LIKE '%|PERFORM_ACTION|%'
          );
        IF team_name IS NULL THEN
            RAISE EXCEPTION 'Cannot route Galley defect %: the Maintenance team recorded by its Reviewer is missing, inactive, or has no eligible active member.', item.reference;
        END IF;

        SELECT count(*), min(ca.assigned_team), min(ca.status)
          INTO action_count, action_team, action_status
        FROM corrective_actions_dtl ca
        WHERE ca.operator_id=item.operator_id
          AND ca.defect_id=item.defect_id
          AND ca.isactive=1;

        IF action_count > 1 THEN
            RAISE EXCEPTION 'Cannot route Galley defect %: multiple active corrective actions already exist.', item.reference;
        ELSIF action_count = 1 THEN
            IF action_team IS DISTINCT FROM team_name OR action_status <> 'ASSIGNED' THEN
                RAISE EXCEPTION 'Cannot route Galley defect %: its existing active corrective action conflicts with the Reviewer-approved Maintenance team or status.', item.reference;
            END IF;
            SELECT ca.id INTO action_id
            FROM corrective_actions_dtl ca
            WHERE ca.operator_id=item.operator_id
              AND ca.defect_id=item.defect_id
              AND ca.isactive=1;
        ELSE
            action_id := gen_random_uuid();
            INSERT INTO corrective_actions_dtl(
                id, operator_id, defect_id, created_by_user_id, assigned_team,
                description, priority, status
            ) VALUES (
                action_id, item.operator_id, item.defect_id, 'system-migration-v41', team_name,
                'Reviewer-approved cabin defect: '||item.title,
                CASE WHEN item.severity IN ('HIGH','CRITICAL') THEN item.severity ELSE 'MEDIUM' END,
                'ASSIGNED'
            );
        END IF;

        UPDATE defects_dtl
        SET status='ACTION_ASSIGNED',
            assigned_to=team_name,
            assigned_user_id=NULL,
            assigned_team=team_name,
            due_at=NULL,
            updated_at=now(),
            version=version+1
        WHERE operator_id=item.operator_id
          AND id=item.defect_id
          AND status='INSPECTION_REQUIRED';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Cannot route Galley defect %: its status changed during migration.', item.reference;
        END IF;

        INSERT INTO defect_events_dtl(
            operator_id, defect_id, event_type, from_status, to_status, note,
            actor_id, entity_type, entity_id, previous_state, new_state
        ) VALUES (
            item.operator_id, item.defect_id, 'ACTION_ASSIGNED', item.previous_status,
            'ACTION_ASSIGNED', 'Legacy Reviewer approval routed directly to Maintenance; inspection was not required.',
            'system-migration-v41', 'CORRECTIVE_ACTION', action_id,
            jsonb_build_object('status', item.previous_status, 'reviewApprovalEventId', item.approval_event_id),
            jsonb_build_object('status', 'ACTION_ASSIGNED', 'maintenanceTeam', team_name,
                               'maintenanceTeamId', team_id, 'correctiveActionId', action_id,
                               'reviewerId', item.reviewer_id, 'backfilled', true)
        );
    END LOOP;
END $$;
