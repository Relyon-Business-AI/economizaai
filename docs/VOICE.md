# Voz do Economiza AI

Guia de tom para todo texto que o usuário lê (app, push, e-mail, WhatsApp, erros da API, painel do lojista, admin). Aprovado 2026-11-09.

## Quem fala
Um amigo que entende de preço de mercado. Próximo, direto, descontraído — sem gíria e sem forçar a barra.

## Princípios
1. **"A gente" / "você".** Nada de voz passiva nem "o sistema".
2. **O que importa pro usuário primeiro**, o porquê depois (só se ajudar).
3. **Curto.** Máx. 2 frases por mensagem; se precisa de 3, está explicando demais.
4. **Erro sempre diz o que fazer agora.** Nunca culpa o usuário, nunca termina só em "falhou".
5. **Leveza onde cabe** (sucesso, vazio, onboarding). Erro, cobrança e dinheiro: sério e claro, sem piada.
6. **Sem emoji** em mensagem de sistema (push pode ter um, com moderação).
7. **"Pra"** ok em texto curto de app; e-mail e termos usam "para".

## Vocabulário
| Evitar | Usar |
|---|---|
| NFC-e, cupom fiscal, chave de acesso | nota / "os números da nota" |
| portal SEFAZ, portal de notas do estado | site da Receita do seu estado |
| EAN | código de barras |
| CNPJ, razão social | nome do mercado (CNPJ só onde é inevitável) |
| recurso, solicitação, processar | a coisa concreta: nota, produto, pedido |
| ticket médio, merge, observação | gasto médio por compra, juntar, preço |
| "Não foi possível…" | "Não conseguimos…" + próximo passo |

## Glossário fixo (decidido 2026-11-09)
| Conceito | Termo | Nunca |
|---|---|---|
| a nota | **nota** | nota fiscal, NFC-e, NF-e, cupom |
| a chave de acesso | **os números da nota** | chave, chave de acesso, 44 dígitos |
| grupo que divide as notas | **casa** ("família" só quando fala da comunidade) | domicílio |
| linha da nota | **produto** ("item" só no contador da lista de compras) | item |
| nota em andamento | **Lendo sua nota…** / **Esperando a Receita responder** | processando, na fila, aguardando o portal |
| aba de notas | **Notas** | Minhas notas, lista de notas |
| seguir um mercado | **acompanhar** | monitorar, assistidos |
| config. do aparelho | **ajustes do celular** | configurações do dispositivo |
| títulos | sentence case | Title Case |
| marca | **Economiza AI** em todo texto visível | economizai (com um "a" só), economizaai em texto corrido |

**Marca:** "Economiza AI" em todo texto que alguém lê. "economizaai" só em URLs, endereços de e-mail e identificadores técnicos. "economizai" (com um "a" só) nunca.

**Imperativo:** sempre na forma formal em frases ("Tente de novo", "Confira os números", "Escaneie o QR code") — nunca "tenta/confere/escaneia". Botões no infinitivo ("Tentar de novo"). Erro: "Não conseguimos… " + "Tente de novo." — nunca "Erro ao…" / "Não foi possível…".

## Exemplos
- *Não achamos esse produto exato com preço. Abaixo, marcas e tamanhos como referência de faixa de preço.* → **Ainda não temos o preço desse produto exato. Separamos opções parecidas pra você ter uma ideia de quanto custa.**
- *Não foi possível consultar esta nota no portal de notas do estado (RS). Tente novamente em alguns minutos.* → **O site da Receita do RS não respondeu agora. Tenta de novo daqui a pouco, que sua nota não se perdeu.**
- *O recurso solicitado não foi encontrado.* → **Não encontramos o que você procurou.**
- *Arquivo vazio. Envie uma foto válida.* → **A foto não chegou direito. Tenta mandar de novo?**

## Por superfície
- **App do usuário, push, e-mail, WhatsApp:** guia completo.
- **Painel do lojista (B2B):** mesma voz, um degrau mais formal (sem "tenta", prefere "tente"); CNPJ/EAN aceitáveis quando o lojista precisa deles.
- **Admin interno:** termos técnicos são ok (o público somos nós); corrigir só clareza, consistência e erros.
- **Inglês (`messages_en`):** mesma voz em inglês natural e casual — não traduzir literal.
