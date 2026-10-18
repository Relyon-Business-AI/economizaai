-- Gabarito da taxonomia: uma definição (description_pt) e exemplos clássicos (examples_pt)
-- para CADA categoria e subcategoria. É a fonte única que alimenta o prompt do classificador
-- de IA (AiFineCategoryClassifier) — quanto melhor o gabarito, mais confiável a categorização
-- e menos correção manual. Reutilizável também como texto de ajuda no admin/app.
-- description = o que PERTENCE ali (e exclusões importantes); examples = itens clássicos.

ALTER TABLE categories ADD COLUMN description_pt TEXT;
ALTER TABLE categories ADD COLUMN examples_pt TEXT;

-- ── Categorias (nível 1) ────────────────────────────────────────────────────
UPDATE categories SET
  description_pt = 'Frutas, verduras, legumes e temperos frescos, in natura, a granel ou em bandeja. Não inclui sucos, conservas, enlatados nem congelados.',
  examples_pt = 'Banana, tomate, alface, cebola, maçã, salsinha'
  WHERE slug = 'hortifruti';
UPDATE categories SET
  description_pt = 'Carnes, aves, pescados e frutos do mar frescos, resfriados ou congelados in natura. Frios e embutidos (presunto, mortadela, linguiça) também entram aqui.',
  examples_pt = 'Picanha, frango, filé de tilápia, camarão, linguiça'
  WHERE slug = 'carnes';
UPDATE categories SET
  description_pt = 'Leite e derivados (queijo, iogurte, manteiga, requeijão, creme de leite) e ovos. Leite de coco NÃO é laticínio (vai em Mercearia).',
  examples_pt = 'Leite integral, queijo mussarela, iogurte, manteiga, ovos'
  WHERE slug = 'laticinios-ovos';
UPDATE categories SET
  description_pt = 'Pães, bolos e produtos de panificação e confeitaria.',
  examples_pt = 'Pão francês, pão de forma, bolo, panetone, croissant'
  WHERE slug = 'padaria';
UPDATE categories SET
  description_pt = 'Secos e mantimentos de despensa não perecíveis: grãos, massas, farináceos, enlatados, molhos, temperos secos, óleos, açúcar, café. Base de cozinha.',
  examples_pt = 'Arroz, feijão, macarrão, extrato de tomate, azeite, café'
  WHERE slug = 'mercearia';
UPDATE categories SET
  description_pt = 'Guloseimas e petiscos industrializados de pacote: doces, balas, chocolates, biscoitos e salgadinhos.',
  examples_pt = 'Chocolate, bala, biscoito, Ruffles, pipoca'
  WHERE slug = 'doces-snacks';
UPDATE categories SET
  description_pt = 'Alimentos industrializados prontos ou semiprontos congelados, além de sorvetes. Carne in natura congelada NÃO entra aqui (vai em Carnes).',
  examples_pt = 'Pizza congelada, lasanha, hambúrguer congelado, batata palito, sorvete'
  WHERE slug = 'congelados';
UPDATE categories SET
  description_pt = 'Bebidas não alcoólicas: água, sucos, refrigerantes, chás, café pronto, energéticos e isotônicos.',
  examples_pt = 'Água mineral, suco, Coca-Cola, chá gelado, energético'
  WHERE slug = 'bebidas';
UPDATE categories SET
  description_pt = 'Bebidas com teor alcoólico: cervejas, vinhos, destilados e afins.',
  examples_pt = 'Cerveja, vinho, vodka, whisky, cachaça'
  WHERE slug = 'bebidas-alcoolicas';
UPDATE categories SET
  description_pt = 'Produtos de limpeza doméstica e de lavanderia.',
  examples_pt = 'Detergente, sabão em pó, água sanitária, desinfetante, amaciante'
  WHERE slug = 'limpeza';
UPDATE categories SET
  description_pt = 'Higiene e cuidado pessoal: banho, cabelo, higiene oral, cosméticos e cuidados do corpo.',
  examples_pt = 'Shampoo, sabonete, creme dental, desodorante, absorvente, fralda'
  WHERE slug = 'higiene';
UPDATE categories SET
  description_pt = 'Itens não alimentares de uso da casa: papel (higiênico, toalha), descartáveis, utilidades domésticas, pilhas, lâmpadas e ferramentas.',
  examples_pt = 'Papel higiênico, guardanapo, copo descartável, pilha, lâmpada'
  WHERE slug = 'casa-descartaveis';
UPDATE categories SET
  description_pt = 'Produtos de saúde de farmácia: medicamentos, suplementos, vitaminas e primeiros socorros.',
  examples_pt = 'Dipirona, vitamina C, whey protein, curativo, álcool 70'
  WHERE slug = 'saude';
UPDATE categories SET
  description_pt = 'Produtos para animais de estimação.',
  examples_pt = 'Ração, areia de gato, petisco para cão, shampoo pet'
  WHERE slug = 'pet';
UPDATE categories SET
  description_pt = 'Itens que não pertencem a nenhuma categoria de supermercado ou farmácia suportada (vestuário, papelaria, brinquedo, eletrônico). Use só quando realmente não couber em outra categoria.',
  examples_pt = 'Vestuário, caderno, brinquedo, carregador'
  WHERE slug = 'outros';

-- ── Hortifrúti ──────────────────────────────────────────────────────────────
UPDATE categories SET description_pt = 'Frutas frescas in natura.',
  examples_pt = 'Banana, maçã, mamão, laranja, melancia, uva' WHERE slug = 'frutas';
UPDATE categories SET description_pt = 'Legumes, verduras e hortaliças frescas.',
  examples_pt = 'Tomate, batata, cebola, alface, cenoura, brócolis' WHERE slug = 'legumes-verduras';
UPDATE categories SET description_pt = 'Ervas e temperos frescos (não secos/industrializados).',
  examples_pt = 'Salsinha, cebolinha, coentro, manjericão, alecrim, hortelã' WHERE slug = 'temperos-ervas';
UPDATE categories SET description_pt = 'Itens de hortifrúti que não são fruta, legume/verdura nem tempero fresco.',
  examples_pt = 'Cogumelo fresco, coco seco, castanhas a granel' WHERE slug = 'hortifruti-outros';

-- ── Carnes ──────────────────────────────────────────────────────────────────
UPDATE categories SET description_pt = 'Carne bovina em qualquer corte, incluindo carne moída.',
  examples_pt = 'Picanha, alcatra, coxão, patinho, costela, carne moída' WHERE slug = 'carnes-bovinas';
UPDATE categories SET description_pt = 'Aves.',
  examples_pt = 'Frango, peito de frango, coxa, sobrecoxa, peru, chester' WHERE slug = 'aves';
UPDATE categories SET description_pt = 'Carne suína e também frios e embutidos em geral.',
  examples_pt = 'Bacon, lombo, pernil, linguiça, salsicha, presunto, mortadela' WHERE slug = 'suinos';
UPDATE categories SET description_pt = 'Peixes.',
  examples_pt = 'Tilápia, salmão, sardinha, merluza, bacalhau, atum fresco' WHERE slug = 'peixes';
UPDATE categories SET description_pt = 'Frutos do mar, exceto peixes.',
  examples_pt = 'Camarão, lula, polvo, marisco' WHERE slug = 'frutos-do-mar';
UPDATE categories SET description_pt = 'Carnes que não se encaixam nas demais subcategorias.',
  examples_pt = 'Cordeiro, carneiro, coelho, carne de caça' WHERE slug = 'carnes-outros';

-- ── Laticínios e ovos ───────────────────────────────────────────────────────
UPDATE categories SET description_pt = 'Leite de vaca fluido ou em pó. Leite de coco NÃO entra aqui.',
  examples_pt = 'Leite integral, leite desnatado, leite em pó' WHERE slug = 'leite';
UPDATE categories SET description_pt = 'Queijos de todos os tipos.',
  examples_pt = 'Mussarela, parmesão, provolone, minas, coalho, cheddar' WHERE slug = 'queijos';
UPDATE categories SET description_pt = 'Iogurtes e leites fermentados.',
  examples_pt = 'Iogurte, Activia, Danoninho, leite fermentado' WHERE slug = 'iogurtes';
UPDATE categories SET description_pt = 'Ovos.',
  examples_pt = 'Ovos brancos, ovos vermelhos, ovos de codorna, dúzia de ovos' WHERE slug = 'ovos';
UPDATE categories SET description_pt = 'Derivados do leite que não são queijo nem iogurte.',
  examples_pt = 'Manteiga, margarina, requeijão, creme de leite, leite condensado, nata' WHERE slug = 'outros-laticinios';

-- ── Padaria ─────────────────────────────────────────────────────────────────
UPDATE categories SET description_pt = 'Pães de todos os tipos.',
  examples_pt = 'Pão francês, pão de forma, baguete, bisnaga' WHERE slug = 'paes';
UPDATE categories SET description_pt = 'Bolos, tortas e itens de confeitaria.',
  examples_pt = 'Bolo, torta, croissant, sonho, panetone' WHERE slug = 'bolos-confeitaria';
UPDATE categories SET description_pt = 'Itens de padaria fora de pães e confeitaria.',
  examples_pt = 'Rosca, salgado assado, folhado' WHERE slug = 'padaria-outros';

-- ── Mercearia ───────────────────────────────────────────────────────────────
UPDATE categories SET description_pt = 'Grãos, cereais e massas secas.',
  examples_pt = 'Arroz, feijão, macarrão, farinha, lentilha, grão de bico' WHERE slug = 'graos-massas';
UPDATE categories SET description_pt = 'Alimentos em lata ou conserva.',
  examples_pt = 'Milho verde, ervilha, extrato de tomate, atum em lata, azeitona, palmito' WHERE slug = 'enlatados-conservas';
UPDATE categories SET description_pt = 'Molhos, condimentos, óleos e temperos secos ou industrializados.',
  examples_pt = 'Ketchup, maionese, azeite, vinagre, orégano, caldo, shoyu' WHERE slug = 'molhos-temperos';
UPDATE categories SET description_pt = 'Cereais matinais e itens de café da manhã.',
  examples_pt = 'Granola, sucrilhos, aveia, mingau' WHERE slug = 'matinais';
UPDATE categories SET description_pt = 'Secos de despensa fora das demais subcategorias (açúcar, fermento, leite de coco, misturas em pó).',
  examples_pt = 'Açúcar, fermento, leite de coco, gelatina em pó, fermento biológico' WHERE slug = 'mercearia-outros';

-- ── Doces e snacks ──────────────────────────────────────────────────────────
UPDATE categories SET description_pt = 'Doces, balas, biscoitos e sobremesas industrializadas (que não sejam chocolate nem salgadinho).',
  examples_pt = 'Bala, pirulito, biscoito, bolacha, wafer, paçoca, brigadeiro' WHERE slug = 'doces';
UPDATE categories SET description_pt = 'Snacks salgados de pacote.',
  examples_pt = 'Ruffles, Doritos, Cheetos, pipoca, amendoim, Fandangos, torcida' WHERE slug = 'salgadinhos';
UPDATE categories SET description_pt = 'Chocolates e achocolatados.',
  examples_pt = 'Barra de chocolate, bombom, KitKat, Nescau, Toddy' WHERE slug = 'chocolates';
UPDATE categories SET description_pt = 'Doces e snacks que não são doce comum, salgadinho nem chocolate.',
  examples_pt = 'Goma de mascar, marshmallow, barra de cereal doce' WHERE slug = 'doces-snacks-outros';

-- ── Bebidas ─────────────────────────────────────────────────────────────────
UPDATE categories SET description_pt = 'Água mineral, com ou sem gás. Água sanitária NÃO entra aqui (é Limpeza).',
  examples_pt = 'Água mineral, água com gás' WHERE slug = 'agua';
UPDATE categories SET description_pt = 'Sucos e néctares, prontos, concentrados ou em pó, e água de coco.',
  examples_pt = 'Suco de laranja, néctar, Del Valle, suco em pó, água de coco' WHERE slug = 'sucos';
UPDATE categories SET description_pt = 'Refrigerantes.',
  examples_pt = 'Coca-Cola, Guaraná, Pepsi, Fanta, Sprite, água tônica' WHERE slug = 'refrigerantes';
UPDATE categories SET description_pt = 'Café (pó ou grão) e chás, prontos ou para preparo.',
  examples_pt = 'Café, cappuccino, chá preto, chá gelado, mate, erva-mate' WHERE slug = 'cafe-cha';
UPDATE categories SET description_pt = 'Bebidas não alcoólicas fora de água, suco, refrigerante e café/chá: energéticos e isotônicos.',
  examples_pt = 'Red Bull, Monster, Gatorade, Powerade, isotônico' WHERE slug = 'bebidas-outros';

-- ── Bebidas alcoólicas ──────────────────────────────────────────────────────
UPDATE categories SET description_pt = 'Cervejas e chopp.',
  examples_pt = 'Heineken, Brahma, Budweiser, Eisenbahn, chopp' WHERE slug = 'cervejas';
UPDATE categories SET description_pt = 'Vinhos e espumantes.',
  examples_pt = 'Vinho tinto, vinho branco, espumante, prosecco' WHERE slug = 'vinhos';
UPDATE categories SET description_pt = 'Destilados e licores.',
  examples_pt = 'Vodka, whisky, cachaça, gin, rum, licor' WHERE slug = 'destilados';
UPDATE categories SET description_pt = 'Alcoólicas fora de cerveja, vinho e destilado.',
  examples_pt = 'Bebida alcoólica mista (ice), sidra, saquê' WHERE slug = 'bebidas-alcoolicas-outros';

-- ── Casa e descartáveis ─────────────────────────────────────────────────────
UPDATE categories SET description_pt = 'Papéis de uso doméstico.',
  examples_pt = 'Papel higiênico, guardanapo, toalha de papel, lenço de papel' WHERE slug = 'papel';
UPDATE categories SET description_pt = 'Descartáveis e embalagens de uso doméstico.',
  examples_pt = 'Copo descartável, prato descartável, papel alumínio, filme PVC, saco de lixo, luva' WHERE slug = 'descartaveis';
UPDATE categories SET description_pt = 'Utilidades e itens não alimentares da casa que não são descartáveis.',
  examples_pt = 'Pilha, lâmpada, fósforo, vela, cola, fita adesiva, carvão' WHERE slug = 'utilidades';
UPDATE categories SET description_pt = 'Itens de casa fora de papel, descartável ou utilidade.',
  examples_pt = 'Isqueiro, item doméstico avulso' WHERE slug = 'casa-descartaveis-outros';

-- ── Saúde ───────────────────────────────────────────────────────────────────
UPDATE categories SET description_pt = 'Medicamentos e remédios.',
  examples_pt = 'Dipirona, paracetamol, antigripal, pomada, soro fisiológico' WHERE slug = 'medicamentos';
UPDATE categories SET description_pt = 'Suplementos alimentares e vitaminas.',
  examples_pt = 'Whey protein, vitamina C, colágeno, creatina, ômega 3' WHERE slug = 'suplementos';
UPDATE categories SET description_pt = 'Itens de saúde ou farmácia fora de medicamento e suplemento.',
  examples_pt = 'Curativo, álcool 70, termômetro, preservativo, teste de gravidez' WHERE slug = 'saude-outros';
