-- The trigger function was originally written for UPDATE and returned NEW for
-- every operation. A BEFORE DELETE trigger must return OLD to permit deletion;
-- returning NEW silently cancels the draft deletion and looks like a conflict.
CREATE OR REPLACE FUNCTION protect_published_form_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status='PUBLISHED' THEN
    RAISE EXCEPTION 'Published form versions are immutable';
  END IF;
  IF TG_OP='DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$$;
