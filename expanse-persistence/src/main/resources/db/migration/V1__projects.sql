CREATE TABLE projects (
    id VARCHAR(36) PRIMARY KEY,
    revision BIGINT NOT NULL CHECK (revision >= 0),
    name VARCHAR(200) NOT NULL,
    document TEXT NOT NULL
);
