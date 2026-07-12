-- Org billing profile: the seller identity a printable invoice / credit-note / receipt header needs.
-- All nullable — an org with none set still renders a valid document (header falls back to org.name).
-- See stories/document_pdf_rendering.md (Part A).
ALTER TABLE org
    ADD COLUMN legal_name              VARCHAR(255),
    ADD COLUMN tax_registration_number VARCHAR(64),
    ADD COLUMN address_line1           VARCHAR(255),
    ADD COLUMN address_line2           VARCHAR(255),
    ADD COLUMN city                    VARCHAR(128),
    ADD COLUMN country                 VARCHAR(128),
    ADD COLUMN phone                   VARCHAR(32),
    ADD COLUMN contact_email           VARCHAR(255),
    ADD COLUMN logo_object_key         VARCHAR(512);
