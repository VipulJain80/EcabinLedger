-- Maintenance's Close Submitted option historically targeted verification.
-- Correct only cases still awaiting verification whose immutable event proves
-- that Maintenance explicitly submitted Close and whose linked action completed.
-- Finalized, verified, or otherwise unrelated legacy cases are left untouched.
DO $$
DECLARE
    item record;
    completed_action_count integer;
BEGIN
    FOR item IN
        SELECT d.operator_id,
               d.id AS defect_id,
               d.reference,
               close_event.id AS close_event_id,
               close_event.entity_id AS action_id,
               close_event.actor_id AS maintenance_actor
        FROM defects_dtl d
        JOIN LATERAL (
            SELECT ev.id, ev.entity_id, ev.actor_id
            FROM defect_events_dtl ev
            WHERE ev.operator_id=d.operator_id
              AND ev.defect_id=d.id
              AND ev.event_type='ACTION_CLOSE_SUBMITTED'
              AND ev.from_status='IN_PROGRESS'
              AND ev.to_status='AWAITING_VERIFICATION'
              AND ev.entity_type='CORRECTIVE_ACTION'
            ORDER BY ev.occurred_at DESC, ev.id DESC
            LIMIT 1
        ) close_event ON true
        WHERE d.isactive=1
          AND d.status='AWAITING_VERIFICATION'
          AND NOT EXISTS (
              SELECT 1 FROM verifications_dtl verification
              WHERE verification.operator_id=d.operator_id
                AND verification.defect_id=d.id
                AND verification.isactive=1
          )
          AND NOT EXISTS (
              SELECT 1 FROM closure_approvals_dtl approval
              WHERE approval.operator_id=d.operator_id
                AND approval.defect_id=d.id
          )
    LOOP
        SELECT count(*) INTO completed_action_count
        FROM corrective_actions_dtl action
        WHERE action.operator_id=item.operator_id
          AND action.defect_id=item.defect_id
          AND action.id=item.action_id
          AND action.isactive=1
          AND action.status='COMPLETED';

        IF completed_action_count <> 1 THEN
            RAISE EXCEPTION 'Cannot route defect % to Closure Team: its Close Submitted event does not resolve to one active completed corrective action.', item.reference;
        END IF;

        UPDATE defects_dtl
        SET status='APPROVAL_REQUIRED',
            updated_at=now(),
            version=version+1
        WHERE operator_id=item.operator_id
          AND id=item.defect_id
          AND isactive=1
          AND status='AWAITING_VERIFICATION';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Cannot route defect % to Closure Team: its status changed during migration.', item.reference;
        END IF;

        INSERT INTO defect_events_dtl(
            operator_id, defect_id, event_type, from_status, to_status, note,
            actor_id, entity_type, entity_id, previous_state, new_state
        ) VALUES (
            item.operator_id, item.defect_id, 'ACTION_CLOSE_ROUTING_CORRECTED',
            'AWAITING_VERIFICATION', 'APPROVAL_REQUIRED',
            'Legacy Maintenance Close submission routed directly to Closure Team; no verification was performed.',
            'system-migration-v42', 'CORRECTIVE_ACTION', item.action_id,
            jsonb_build_object('status','AWAITING_VERIFICATION','closeSubmissionEventId',item.close_event_id),
            jsonb_build_object('status','APPROVAL_REQUIRED','maintenanceActionId',item.action_id,
                               'maintenanceActor',item.maintenance_actor,'backfilled',true)
        );
    END LOOP;
END $$;
