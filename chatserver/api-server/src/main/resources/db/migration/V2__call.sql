-- Call types: dm (one-on-one) or group
CREATE TABLE calls (
                       id UUID PRIMARY KEY,
                       room_name VARCHAR(255) UNIQUE NOT NULL,
                       call_type VARCHAR(10) NOT NULL CHECK (call_type IN ('DM', 'GROUP')),

    -- For DM calls
                       dm_id UUID REFERENCES dm_conversations(id) ON DELETE CASCADE,

    -- For group calls
                       group_id UUID REFERENCES groups(id) ON DELETE CASCADE,

    -- Metadata
                       initiated_by UUID NOT NULL REFERENCES users(id),
                       status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ENDED')),

                       created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                       ended_at TIMESTAMPTZ,

    -- Ensure only one active call per conversation at a time
                       CONSTRAINT call_target_check CHECK (
                           (dm_id IS NOT NULL AND group_id IS NULL) OR
                           (dm_id IS NULL AND group_id IS NOT NULL)
                           )
);

CREATE INDEX idx_calls_dm_id ON calls(dm_id) WHERE dm_id IS NOT NULL;
CREATE INDEX idx_calls_group_id ON calls(group_id) WHERE group_id IS NOT NULL;
CREATE INDEX idx_calls_status ON calls(status);
CREATE INDEX idx_calls_room_name ON calls(room_name);

-- Track participants who have joined
CREATE TABLE call_participants (
                                   call_id UUID NOT NULL REFERENCES calls(id) ON DELETE CASCADE,
                                   user_id UUID NOT NULL REFERENCES users(id),
                                   joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                   left_at TIMESTAMPTZ,

                                   PRIMARY KEY (call_id, user_id)
);

CREATE INDEX idx_call_participants_user_id ON call_participants(user_id);
CREATE INDEX idx_call_participants_active ON call_participants(call_id) WHERE left_at IS NULL;