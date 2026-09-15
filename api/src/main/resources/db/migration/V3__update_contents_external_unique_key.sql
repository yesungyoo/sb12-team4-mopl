ALTER TABLE contents
DROP INDEX uq_contents_external,
    ADD CONSTRAINT uq_contents_external
        UNIQUE (external_source, type, external_id);