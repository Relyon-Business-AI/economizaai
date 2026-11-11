-- Linha de nota cujo preço/quantidade/EAN/produto foi alterado pelo usuário, ou que foi
-- adicionada à mão: continua no histórico da casa, mas não alimenta o índice colaborativo.
ALTER TABLE receipt_items
    ADD COLUMN user_modified BOOLEAN NOT NULL DEFAULT FALSE;
