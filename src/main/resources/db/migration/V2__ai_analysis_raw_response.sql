-- =============================================================================
-- SentinelAI :: raw model response
--
-- A rejected or failed analysis is only diagnosable if the raw text that came
-- back is preserved. Without it, "the validator rejected this" is unfalsifiable:
-- the failure could be a prompt problem, a provider problem, or a model that had
-- genuinely misunderstood the contract.
--
-- Stored for every attempt, not just failures — the response is the primary
-- evidence when a validation rule itself is suspected of being wrong.
-- =============================================================================

alter table ai_analyses
    add column raw_response text;

comment on column ai_analyses.raw_response is
    'Verbatim model reply for the last attempt, retained for auditing rejected analyses';

-- Detecting "same broken output retried" quickly: same model, same prompt
-- version, same verdict.
create index idx_ai_analyses_rejected
    on ai_analyses (model_name, prompt_version, created_at desc)
    where status = 'REJECTED';