DO $task_onboarding$
BEGIN
    IF to_regclass('tb_task_config') IS NULL THEN
        RETURN;
    END IF;

    IF (
        SELECT count(*) = 3
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'tb_task_config'
          AND is_nullable = 'NO'
          AND (
              (column_name = 'onboarding_step'
                  AND column_default = '''RESULT_CODE''::character varying')
              OR (column_name = 'onboarding_status'
                  AND column_default = '''ACTIVE''::character varying')
              OR (column_name = 'onboarding_context'
                  AND column_default = '''{}''::text')
          )
    ) THEN
        RETURN;
    END IF;

    ALTER TABLE tb_task_config
        ADD COLUMN IF NOT EXISTS onboarding_step varchar(40);
    ALTER TABLE tb_task_config
        ADD COLUMN IF NOT EXISTS onboarding_status varchar(40);
    ALTER TABLE tb_task_config
        ADD COLUMN IF NOT EXISTS onboarding_context text;

    UPDATE tb_task_config
    SET onboarding_step = COALESCE(onboarding_step, 'RESULT_CODE'),
        onboarding_status = COALESCE(onboarding_status, 'ACTIVE'),
        onboarding_context = COALESCE(onboarding_context, '{}')
    WHERE onboarding_step IS NULL
       OR onboarding_status IS NULL
       OR onboarding_context IS NULL;

    ALTER TABLE tb_task_config
        ALTER COLUMN onboarding_step SET DEFAULT 'RESULT_CODE',
        ALTER COLUMN onboarding_step SET NOT NULL,
        ALTER COLUMN onboarding_status SET DEFAULT 'ACTIVE',
        ALTER COLUMN onboarding_status SET NOT NULL,
        ALTER COLUMN onboarding_context SET DEFAULT '{}',
        ALTER COLUMN onboarding_context SET NOT NULL;
END
$task_onboarding$;
^^^
DO $task_onboarding_indexes$
DECLARE
    expected_run_result_index regclass;
    expected_run_result_index_valid boolean;
BEGIN
    IF to_regclass('tb_task_result') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_task_result_onboarding_marker
            ON tb_task_result (task_config_id, source_description, id);
    END IF;

    IF to_regclass('tb_task_run') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_task_run_onboarding_marker
            ON tb_task_run (task_config_id, reason, id);
    END IF;

    IF to_regclass('tb_task_run_result') IS NOT NULL THEN
        expected_run_result_index := to_regclass('uk_task_run_result_run_result');
        IF expected_run_result_index IS NOT NULL THEN
            SELECT index_row.indisunique
                   AND index_row.indisvalid
                   AND index_row.indisready
                   AND index_row.indpred IS NULL
                   AND index_row.indexprs IS NULL
                   AND index_row.indnkeyatts = 2
                   AND (
                       SELECT array_agg(attribute_row.attname ORDER BY key_row.ordinality)
                       FROM unnest(index_row.indkey) WITH ORDINALITY
                           AS key_row(attnum, ordinality)
                       JOIN pg_attribute attribute_row
                         ON attribute_row.attrelid = index_row.indrelid
                        AND attribute_row.attnum = key_row.attnum
                   ) = ARRAY['task_run_id', 'task_result_id']::name[]
            INTO expected_run_result_index_valid
            FROM pg_index index_row
            JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
            WHERE index_class.oid = expected_run_result_index
              AND index_class.relnamespace = current_schema()::regnamespace
              AND index_row.indrelid = 'tb_task_run_result'::regclass;
            IF NOT COALESCE(expected_run_result_index_valid, false) THEN
                RAISE EXCEPTION 'uk_task_run_result_run_result has wrong definition';
            END IF;
        ELSE
            IF EXISTS (
                SELECT 1
                FROM tb_task_run_result
                GROUP BY task_run_id, task_result_id
                HAVING count(*) > 1
            ) THEN
                RAISE EXCEPTION 'Cannot add uk_task_run_result_run_result: duplicate task-run/result links exist';
            END IF;
            CREATE UNIQUE INDEX uk_task_run_result_run_result
                ON tb_task_run_result (task_run_id, task_result_id);
        END IF;
    END IF;
END
$task_onboarding_indexes$;
^^^
