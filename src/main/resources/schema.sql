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
BEGIN
    IF to_regclass('tb_task_result') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_task_result_onboarding_marker
            ON tb_task_result (task_config_id, source_description, id);
    END IF;

    IF to_regclass('tb_task_run') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_task_run_onboarding_marker
            ON tb_task_run (task_config_id, reason, id);
    END IF;
END
$task_onboarding_indexes$;
^^^
