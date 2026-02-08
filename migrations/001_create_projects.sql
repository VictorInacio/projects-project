-- Migration: 001_create_projects
-- Creates the projects table with proper constraints
-- Compatible with SQLite (local dev) and easily adapted for Snowflake (prod)

-- Projects table
CREATE TABLE IF NOT EXISTS projects (
    -- UUID v4 stored as TEXT (36 chars with hyphens)
    -- Rationale: no coordination needed, works with distributed systems,
    -- URL-safe, and simple string comparison in SQLite
    id TEXT PRIMARY KEY,

    -- Human-readable project name (1-200 chars, trimmed)
    name TEXT NOT NULL,

    -- Project lifecycle status with CHECK constraint
    -- 'active' = ongoing (default), 'on-hold' = paused, 'archived' = terminal
    status TEXT NOT NULL DEFAULT 'active',

    -- Server timestamps in ISO-8601 format
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    -- Constraints
    CHECK (status IN ('active', 'on-hold', 'archived')),
    CHECK (length(name) >= 1 AND length(name) <= 200)
);

-- Index for efficient listing sorted by creation time (most common query)
CREATE INDEX IF NOT EXISTS idx_projects_created_at ON projects(created_at DESC);

-- Index for filtering by status (optional extension)
CREATE INDEX IF NOT EXISTS idx_projects_status ON projects(status);


-- Seed data for development/demo
-- Note: In production, use a separate seed script or admin tool
INSERT OR IGNORE INTO projects (id, name, status, created_at, updated_at) VALUES
    ('550e8400-e29b-41d4-a716-446655440001', 'Website Redesign', 'active',
     datetime('now'), datetime('now')),
    ('550e8400-e29b-41d4-a716-446655440002', 'Mobile App v2', 'active',
     datetime('now'), datetime('now')),
    ('550e8400-e29b-41d4-a716-446655440003', 'Legacy System Migration', 'on-hold',
     datetime('now'), datetime('now')),
    ('550e8400-e29b-41d4-a716-446655440004', 'Q4 2024 Marketing Campaign', 'archived',
     datetime('now'), datetime('now')),
    ('550e8400-e29b-41d4-a716-446655440005', 'Internal Tools Platform', 'active',
     datetime('now'), datetime('now'));
