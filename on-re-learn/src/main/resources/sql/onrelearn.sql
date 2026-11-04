-- JDBC startup initialization. Use the database selected by the datasource URL.
-- Never drop/reset data. Existing table changes require reviewed ALTER statements.

-- ! No material FK: this receipt must survive deletion until storage cleanup succeeds.
CREATE TABLE IF NOT EXISTS material_deletion (
    material_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_path VARCHAR(1024) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS app_user (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    email VARCHAR(254) COLLATE utf8mb4_bin NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_user_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;


-- * Goal configuration is one row per user; activity progress is calculated separately.
CREATE TABLE IF NOT EXISTS goal (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    daily_quiz_goal INT NOT NULL DEFAULT 10,
    weekly_quiz_goal INT NOT NULL DEFAULT 70,
    daily_material_goal INT NOT NULL DEFAULT 3,
    weekly_material_goal INT NOT NULL DEFAULT 15,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_goal_user UNIQUE (user_id),
    CONSTRAINT fk_goal_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT ck_goal_daily_quiz CHECK (daily_quiz_goal >= 0),
    CONSTRAINT ck_goal_weekly_quiz CHECK (weekly_quiz_goal >= 0),
    CONSTRAINT ck_goal_daily_material CHECK (daily_material_goal >= 0),
    CONSTRAINT ck_goal_weekly_material CHECK (weekly_material_goal >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS grade (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    academic_year INT NOT NULL,
    term INT NOT NULL,
    is_current BIT(1) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_grade_user_year_term UNIQUE (user_id, academic_year, term),
    CONSTRAINT fk_grade_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT ck_grade_year CHECK (academic_year BETWEEN 1 AND 6),
    CONSTRAINT ck_grade_term CHECK (term IN (1, 2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS subject (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    grade_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(255) NOT NULL,
    is_current BIT(1) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX ix_subject_grade (grade_id),
    CONSTRAINT fk_subject_grade FOREIGN KEY (grade_id) REFERENCES grade(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- * Each subject can have only one PDF metadata row per study week.
-- ! RESTRICT keeps subject deletion from silently removing storage-backed material.
CREATE TABLE IF NOT EXISTS material (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    week INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    storage_path VARCHAR(1024) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_material_subject_week UNIQUE (subject_id, week),
    CONSTRAINT fk_material_subject FOREIGN KEY (subject_id) REFERENCES subject(id) ON DELETE RESTRICT,
    CONSTRAINT ck_material_week CHECK (week >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- * Extracted text is derived per PDF page; retries update the same material/page pair.
-- ! Page rows may cascade only after the owning material is deliberately deleted.
CREATE TABLE IF NOT EXISTS material_page_text (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    material_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    page INT NOT NULL,
    text LONGTEXT NOT NULL,
    char_count INT NOT NULL,
    method VARCHAR(32),
    status VARCHAR(16) NOT NULL,
    error VARCHAR(2000),
    extracted_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_material_page_text_material_page UNIQUE (material_id, page),
    CONSTRAINT fk_material_page_text_material FOREIGN KEY (material_id) REFERENCES material(id) ON DELETE CASCADE,
    CONSTRAINT ck_material_page_text_page CHECK (page >= 1),
    CONSTRAINT ck_material_page_text_char_count CHECK (char_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- ! Notes belong to a material and are removed only with its deliberate deletion.
CREATE TABLE IF NOT EXISTS note (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    material_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    page INT NOT NULL,
    content LONGTEXT NOT NULL,
    signals JSON,
    signals_ver LONGTEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_note_material_page UNIQUE (material_id, page),
    CONSTRAINT fk_note_material FOREIGN KEY (material_id) REFERENCES material(id) ON DELETE CASCADE,
    CONSTRAINT ck_note_page CHECK (page >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- * One reusable summary per material retains both canonical and adaptive output.
-- ! Only deliberate material deletion cascades to its summary.
CREATE TABLE IF NOT EXISTS material_summary (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    material_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    content LONGTEXT NOT NULL,
    canonical LONGTEXT,
    adaptive LONGTEXT,
    summary_type LONGTEXT,
    signals_digest LONGTEXT,
    provider LONGTEXT,
    model LONGTEXT,
    prompt_version LONGTEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_material_summary_material UNIQUE (material_id),
    CONSTRAINT fk_material_summary_material FOREIGN KEY (material_id) REFERENCES material(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- * PDF extraction is queued separately from upload so retries survive a server restart.
-- ! The owning material is restricted until its queued/running work is explicitly handled.
CREATE TABLE IF NOT EXISTS extraction_job (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    material_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    type VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL,
    priority INT NOT NULL,
    attempts INT NOT NULL,
    max_attempts INT NOT NULL,
    locked_at DATETIME(6),
    locked_by VARCHAR(128),
    last_error VARCHAR(100),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX ix_extraction_job_claim (status, type, priority, created_at),
    INDEX ix_extraction_job_material (material_id, type, status),
    CONSTRAINT fk_extraction_job_material FOREIGN KEY (material_id) REFERENCES material(id) ON DELETE RESTRICT,
    CONSTRAINT ck_extraction_job_attempts CHECK (attempts >= 0 AND attempts <= max_attempts),
    CONSTRAINT ck_extraction_job_max_attempts CHECK (max_attempts >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;


-- * Each generation creates a new set; source_hash is deliberately not unique.
CREATE TABLE IF NOT EXISTS quiz_set (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 material_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 summary_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
 source_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 title LONGTEXT, provider LONGTEXT, model LONGTEXT, prompt_version LONGTEXT,
 purpose VARCHAR(32) NOT NULL DEFAULT 'GENERAL',
 source_attempt_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin, source_topics JSON, source_item_ids JSON,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_quiz_set_material FOREIGN KEY (material_id) REFERENCES material(id) ON DELETE CASCADE,
 CONSTRAINT ck_quiz_set_purpose CHECK (purpose IN ('GENERAL','WRONG_REVIEW','WEAK_TOPIC')),
 INDEX ix_quiz_set_material_created (material_id, created_at),
 INDEX ix_quiz_set_hash (source_hash),
 INDEX ix_quiz_set_purpose (purpose),
 INDEX ix_quiz_set_attempt (source_attempt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS quiz_item (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 quiz_set_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 item_order INT NOT NULL,
 type VARCHAR(16) NOT NULL,
 question LONGTEXT NOT NULL, choices JSON, answer_key JSON NOT NULL,
 explanation LONGTEXT, topic LONGTEXT, points INT NOT NULL DEFAULT 1,
 evidence JSON, signal_hits JSON,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_quiz_item_set FOREIGN KEY (quiz_set_id) REFERENCES quiz_set(id) ON DELETE CASCADE,
 CONSTRAINT ck_quiz_item_order CHECK (item_order > 0),
 CONSTRAINT ck_quiz_item_points CHECK (points > 0),
 CONSTRAINT ck_quiz_item_type CHECK (type IN ('mcq','tf','short')),
 INDEX ix_quiz_item_order (quiz_set_id, item_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- * Multiple attempts are retained; no cleanup or restart policy is applied by this schema.
CREATE TABLE IF NOT EXISTS quiz_attempt (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 quiz_set_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
 score INT, max_score INT,
 started_at DATETIME(6) NOT NULL, submitted_at DATETIME(6), graded_at DATETIME(6),
 CONSTRAINT fk_quiz_attempt_set FOREIGN KEY (quiz_set_id) REFERENCES quiz_set(id) ON DELETE CASCADE,
 CONSTRAINT ck_quiz_attempt_status CHECK (status IN ('IN_PROGRESS','SUBMITTED','GRADED')),
 INDEX ix_quiz_attempt_user_started (user_id, started_at),
 INDEX ix_quiz_attempt_set_started (quiz_set_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS quiz_answer (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 attempt_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 quiz_item_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 response JSON NOT NULL, is_correct BIT(1), earned INT, feedback TEXT,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_quiz_answer_attempt_item UNIQUE (attempt_id, quiz_item_id),
 CONSTRAINT fk_quiz_answer_attempt FOREIGN KEY (attempt_id) REFERENCES quiz_attempt(id) ON DELETE CASCADE,
 CONSTRAINT fk_quiz_answer_item FOREIGN KEY (quiz_item_id) REFERENCES quiz_item(id) ON DELETE CASCADE,
 INDEX ix_quiz_answer_item (quiz_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- * Retain one study fact per owner, material and Seoul date, independently of recent-card limits.
-- ! material_id is a snapshot scalar so deleting a PDF does not erase past goal progress.
CREATE TABLE IF NOT EXISTS material_study_day (
 id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 material_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 study_date DATE NOT NULL,
 first_at DATETIME(6) NOT NULL,
 last_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_material_study_day UNIQUE (user_id, material_id, study_date),
 CONSTRAINT fk_material_study_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
 INDEX ix_material_study_user_date (user_id, study_date),
 INDEX ix_material_study_user_recent (user_id, last_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
