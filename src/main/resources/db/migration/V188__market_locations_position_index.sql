-- "Mercados perto de um ponto" virou consulta por faixa de latitude/longitude (comparacao de uma
-- nota com os mercados proximos). O indice evita varrer a tabela inteira a cada comparacao.
CREATE INDEX idx_market_locations_position ON market_locations (latitude, longitude)
    WHERE latitude IS NOT NULL AND longitude IS NOT NULL;
