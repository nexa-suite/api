ALTER TABLE logistics.operational_exception_transition
    ADD COLUMN resolution varchar(2000),
    ADD COLUMN outcome varchar(40),
    ADD CONSTRAINT ck_operational_exception_transition_resolution CHECK (
        (to_status IN ('RESOLVED','CLOSED') AND resolution IS NOT NULL AND outcome IS NOT NULL AND length(btrim(resolution)) BETWEEN 1 AND 2000
            AND outcome = 'WARNING_CONDITION_ADDRESSED')
        OR (to_status NOT IN ('RESOLVED','CLOSED') AND resolution IS NULL AND outcome IS NULL)
    );
ALTER TABLE logistics.operational_exception_transition
    DROP CONSTRAINT ck_operational_exception_transition_status;
ALTER TABLE logistics.operational_exception_transition
    ADD CONSTRAINT ck_operational_exception_transition_status CHECK (
        (transition_number = 1 AND from_status IS NULL AND to_status = 'OPEN'
            AND reason_code = 'SOURCE_REPORTED' AND command_type = 'SOURCE' AND responsible_membership_id IS NULL)
        OR (transition_number > 1 AND from_status = 'OPEN' AND to_status = 'CLAIMED'
            AND reason_code = 'DRIVER_CLAIMED' AND command_type = 'CLAIM' AND responsible_membership_id = actor_membership_id)
        OR (transition_number > 1 AND from_status = 'CLAIMED' AND to_status = 'UNDER_REVIEW'
            AND reason_code = 'DRIVER_REVIEW_STARTED' AND command_type = 'REVIEW' AND responsible_membership_id IS NOT NULL)
        OR (transition_number > 1 AND from_status = 'UNDER_REVIEW' AND to_status = 'RESOLVED'
            AND reason_code IN ('DRIVER_DELAY_ADDRESSED','DRIVER_INSTRUCTION_CLARIFIED')
            AND command_type = 'RESOLVE' AND responsible_membership_id IS NOT NULL AND responsible_membership_id = actor_membership_id)
        OR (transition_number > 1 AND from_status = 'RESOLVED' AND to_status = 'CLOSED'
            AND reason_code = 'DRIVER_WARNING_CLOSED' AND command_type = 'CLOSE' AND responsible_membership_id IS NOT NULL AND responsible_membership_id = actor_membership_id)
    );
