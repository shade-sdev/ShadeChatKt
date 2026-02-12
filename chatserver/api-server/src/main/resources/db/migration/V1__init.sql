CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ======================
-- users
-- ======================
CREATE TABLE users (
                       id UUID PRIMARY KEY,
                       username TEXT NOT NULL UNIQUE,
                       display_name TEXT NOT NULL,
                       avatar_url TEXT NULL,
                       status TEXT NOT NULL,
                       password_hash TEXT NOT NULL,
                       created_at TIMESTAMPTZ NOT NULL
);

-- ======================
-- groups
-- ======================
CREATE TABLE groups (
                        id UUID PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NULL,
                        admin_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE group_members (
                               group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
                               user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                               PRIMARY KEY (group_id, user_id)
);

CREATE INDEX idx_group_members_user_id ON group_members(user_id);

-- ======================
-- direct message conversations
-- ======================
CREATE TABLE dm_conversations (
                                  id UUID PRIMARY KEY,
                                  participant1_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                  participant2_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                  created_at TIMESTAMPTZ NOT NULL,
                                  CONSTRAINT dm_unique_pair UNIQUE (participant1_id, participant2_id)
);

-- ======================
-- messages
-- ======================
CREATE TABLE messages (
                          id UUID PRIMARY KEY,
                          sender_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

                          group_id UUID NULL REFERENCES groups(id) ON DELETE CASCADE,
                          dm_id UUID NULL REFERENCES dm_conversations(id) ON DELETE CASCADE,

                          content TEXT NOT NULL,
                          created_at TIMESTAMPTZ NOT NULL,
                          edited_at TIMESTAMPTZ NULL,

                          CONSTRAINT message_target_check CHECK (
                              (group_id IS NOT NULL AND dm_id IS NULL)
                                  OR
                              (group_id IS NULL AND dm_id IS NOT NULL)
                              )
);

CREATE INDEX idx_messages_group_id_created_at ON messages(group_id, created_at DESC);
CREATE INDEX idx_messages_dm_id_created_at ON messages(dm_id, created_at DESC);
