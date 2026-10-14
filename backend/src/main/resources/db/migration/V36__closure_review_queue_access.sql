-- The shared review queue is also the worklist for users explicitly routed to closure review.
UPDATE navigation_menu_items_mst
SET required_permissions='REVIEW|APPROVE|ADMINISTER_ORGANIZATION'
WHERE route_key='review-queue';

CREATE FUNCTION include_closure_reviewers_in_queue_navigation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.route_key='review-queue' THEN
    NEW.required_permissions := 'REVIEW|APPROVE|ADMINISTER_ORGANIZATION';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER closure_review_queue_navigation_permissions
BEFORE INSERT OR UPDATE OF required_permissions,route_key ON navigation_menu_items_mst
FOR EACH ROW EXECUTE FUNCTION include_closure_reviewers_in_queue_navigation();
