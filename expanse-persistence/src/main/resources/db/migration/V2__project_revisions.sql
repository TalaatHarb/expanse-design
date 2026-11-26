-- Bounded revision history for undo/redo. undo_to and redo_to point at earlier rows of the same project;
-- undo and redo restore those documents as new revisions, so the history itself is never rewritten.
CREATE TABLE project_revisions (
    project_id VARCHAR(36) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision >= 0),
    document TEXT NOT NULL,
    undo_to BIGINT,
    redo_to BIGINT,
    PRIMARY KEY (project_id, revision)
);
