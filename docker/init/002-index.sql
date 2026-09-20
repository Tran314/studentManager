-- 002-index.sql
-- Adds an index that lets the student search use prefix-match LIKE 'kw%' instead
-- of '%kw%' which would force a full table scan. Plain keywords hit the index;
-- keywords containing '%' or '_' still use escaped substring match for backward
-- compatibility with the LIKE-escape behavior the integration tests rely on.
CREATE INDEX idx_student_sname ON student (sname);
