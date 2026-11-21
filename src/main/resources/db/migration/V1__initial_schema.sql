-- Fresh database only. Coordinate a SINGLE migration owner with the Next.js team.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE users (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),line_user_id text NOT NULL UNIQUE,
 display_name text,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE conversation_sessions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL REFERENCES users(id),
 state text NOT NULL DEFAULT 'COLLECTING' CHECK (state IN ('COLLECTING','WAITING_FOR_USER','READY','ANALYZING','COMPLETED','CLOSED')),
 preferences jsonb NOT NULL DEFAULT '{}'::jsonb, profile_version int NOT NULL DEFAULT 0,
 ready_for_analysis boolean NOT NULL DEFAULT false, awaiting_question text,
 next_message_sequence bigint NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),closed_at timestamptz);
CREATE UNIQUE INDEX uq_open_session ON conversation_sessions(user_id) WHERE closed_at IS NULL;
CREATE TABLE conversation_messages (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),session_id uuid NOT NULL REFERENCES conversation_sessions(id),
 sequence_no bigint NOT NULL, direction text NOT NULL CHECK(direction IN ('IN','OUT')),
 line_message_id text UNIQUE,webhook_event_id text UNIQUE,content text NOT NULL,
 payload jsonb NOT NULL DEFAULT '{}'::jsonb,
 delivery_status text CHECK(delivery_status IN ('PENDING','SENT','FAILED')),
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(session_id,sequence_no));
CREATE INDEX idx_messages_session_order ON conversation_messages(session_id,sequence_no);
CREATE TABLE analysis_jobs (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),session_id uuid NOT NULL REFERENCES conversation_sessions(id),
 message_id uuid REFERENCES conversation_messages(id),
 job_type text NOT NULL CHECK(job_type IN ('CONVERSATION','ANALYSIS')),
 status text NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','PROCESSING','COLLECTING','SCORING','EXPLAINING','COMPLETED','FAILED')),
 idempotency_key text NOT NULL UNIQUE,profile_version int,input_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
 progress smallint NOT NULL DEFAULT 0 CHECK(progress BETWEEN 0 AND 100),attempts int NOT NULL DEFAULT 0,
 max_attempts int NOT NULL DEFAULT 3,run_after timestamptz NOT NULL DEFAULT now(),
 lease_until timestamptz,locked_by text,last_error text,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),completed_at timestamptz);
CREATE INDEX idx_claim_analysis_jobs ON analysis_jobs(run_after,created_at) WHERE job_type='ANALYSIS' AND status IN ('QUEUED','FAILED');
CREATE INDEX idx_expired_job_lease ON analysis_jobs(lease_until) WHERE status IN ('PROCESSING','COLLECTING','SCORING','EXPLAINING');
CREATE TABLE zones (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),slug text NOT NULL UNIQUE,name_th text NOT NULL,name_en text,
 center_lat numeric(10,7) NOT NULL,center_lng numeric(10,7) NOT NULL,boundary jsonb,is_active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE zone_features (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),zone_id uuid NOT NULL UNIQUE REFERENCES zones(id) ON DELETE CASCADE,
 features jsonb NOT NULL DEFAULT '{}'::jsonb,source_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
 confidence numeric(4,3),computed_at timestamptz NOT NULL DEFAULT now(),expires_at timestamptz NOT NULL);
CREATE TABLE properties (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),zone_id uuid REFERENCES zones(id) ON DELETE SET NULL,
 source text NOT NULL,external_id text NOT NULL,title text NOT NULL,rent_price numeric(12,2),
 currency char(3) NOT NULL DEFAULT 'THB',lat numeric(10,7),lng numeric(10,7),listing_url text,
 image_urls jsonb NOT NULL DEFAULT '[]'::jsonb,raw_data jsonb NOT NULL DEFAULT '{}'::jsonb,
 source_updated_at timestamptz,fetched_at timestamptz NOT NULL DEFAULT now(),expires_at timestamptz,
 UNIQUE(source,external_id));
CREATE INDEX idx_properties_zone_rent ON properties(zone_id,rent_price);
CREATE TABLE api_cache (
 cache_key text PRIMARY KEY,provider text NOT NULL,response_data jsonb NOT NULL,
 fetched_at timestamptz NOT NULL DEFAULT now(),expires_at timestamptz NOT NULL);
CREATE INDEX idx_api_cache_expiry ON api_cache(expires_at);
CREATE TABLE analysis_results (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),job_id uuid NOT NULL UNIQUE REFERENCES analysis_jobs(id),
 profile_snapshot jsonb NOT NULL,scoring_version text NOT NULL DEFAULT 'v1',
 result_data jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE notification_outbox (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL REFERENCES users(id),
 analysis_result_id uuid NOT NULL REFERENCES analysis_results(id),
 notification_type text NOT NULL DEFAULT 'ANALYSIS_COMPLETED',
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PROCESSING','SENT','FAILED')),
 payload jsonb NOT NULL DEFAULT '{}'::jsonb,
 attempts int NOT NULL DEFAULT 0,max_attempts int NOT NULL DEFAULT 5,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),lease_until timestamptz,locked_by text,
 last_error text,sent_at timestamptz,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(analysis_result_id,notification_type));
CREATE INDEX idx_notification_pending ON notification_outbox(next_attempt_at) WHERE status IN ('PENDING','FAILED');
