-- Exclusões de contexto por regra do dicionário: a keyword NÃO casa quando o nome
-- do produto contém um dos termos excluídos (ex.: 'agua' não vale em AGUA MICELAR).
ALTER TABLE curated_dictionary_entries ADD COLUMN excluded_terms VARCHAR(500);

UPDATE curated_dictionary_entries SET excluded_terms = 'micelar,oxigenada,boricada,perfumada' WHERE keyword = 'agua';
UPDATE curated_dictionary_entries SET excluded_terms = 'erva,tomate' WHERE keyword = 'mate';
UPDATE curated_dictionary_entries SET excluded_terms = 'whiskas,sache,gato,cao,pet' WHERE keyword = 'pate';
