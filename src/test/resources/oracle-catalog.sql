-- Minimal stand-in for the Oracle ALL_* catalog views used by the services.
-- DBA_* views are intentionally absent so the non-privileged fallbacks run.

CREATE TABLE all_users (username VARCHAR(128), created TIMESTAMP);
CREATE TABLE all_tables (owner VARCHAR(128), table_name VARCHAR(128), num_rows BIGINT, avg_row_len BIGINT);
CREATE TABLE all_views (owner VARCHAR(128), view_name VARCHAR(128));
CREATE TABLE all_tab_columns (
    owner VARCHAR(128), table_name VARCHAR(128), column_name VARCHAR(128), data_type VARCHAR(128),
    data_length INT, data_precision INT, data_scale INT, nullable CHAR(1), column_id INT, avg_col_len BIGINT
);
CREATE TABLE all_indexes (table_owner VARCHAR(128), table_name VARCHAR(128), index_name VARCHAR(128), uniqueness VARCHAR(9));

INSERT INTO all_users VALUES ('APP', TIMESTAMP '2024-01-15 10:00:00');
INSERT INTO all_users VALUES ('EMPTY', TIMESTAMP '2024-02-01 10:00:00');
INSERT INTO all_users VALUES ('SYS', TIMESTAMP '2020-01-01 00:00:00');

INSERT INTO all_tables VALUES ('APP', 'CUSTOMERS', 100, 1048576);
INSERT INTO all_tables VALUES ('APP', 'T$DOCS', 4, 100);
INSERT INTO all_tables VALUES ('SYS', 'OBJ$', 999, 10);

INSERT INTO all_views VALUES ('APP', 'V_CUSTOMERS');

INSERT INTO all_tab_columns VALUES ('APP', 'CUSTOMERS', 'ID', 'NUMBER', 22, 10, 0, 'N', 1, 4);
INSERT INTO all_tab_columns VALUES ('APP', 'CUSTOMERS', 'NAME', 'VARCHAR2', 100, NULL, NULL, 'Y', 2, 20);
INSERT INTO all_tab_columns VALUES ('APP', 'T$DOCS', 'ID', 'NUMBER', 22, 10, 0, 'N', 1, 4);
INSERT INTO all_tab_columns VALUES ('APP', 'T$DOCS', 'DESCRIPCION', 'VARCHAR2', 200, NULL, NULL, 'Y', 2, 30);
INSERT INTO all_tab_columns VALUES ('APP', 'T$DOCS', 'NOMBRE_ARCHIVO', 'VARCHAR2', 200, NULL, NULL, 'Y', 3, 30);
INSERT INTO all_tab_columns VALUES ('APP', 'T$DOCS', 'PDF_DATA', 'BLOB', 4000, NULL, NULL, 'Y', 4, 524288);
-- View columns also appear in ALL_TAB_COLUMNS and must not be treated as tables.
INSERT INTO all_tab_columns VALUES ('APP', 'V_CUSTOMERS', 'ID', 'NUMBER', 22, 10, 0, 'N', 1, 4);

INSERT INTO all_indexes VALUES ('APP', 'CUSTOMERS', 'PK_CUSTOMERS', 'UNIQUE');
INSERT INTO all_indexes VALUES ('APP', 'CUSTOMERS', 'IX_CUSTOMERS_NAME', 'NONUNIQUE');

-- The user table sampled for file names.
CREATE SCHEMA app;
CREATE TABLE app."T$DOCS" (id INT, descripcion VARCHAR(200), nombre_archivo VARCHAR(200), pdf_data BLOB);
INSERT INTO app."T$DOCS" VALUES (1, 'a', 'contrato.pdf', NULL);
INSERT INTO app."T$DOCS" VALUES (2, 'b', 'factura.pdf', NULL);
INSERT INTO app."T$DOCS" VALUES (3, 'c', NULL, NULL);
