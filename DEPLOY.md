# Processo de deploy — economizai

Back e front seguem o mesmo processo: tudo entra em `development`, que sobe sozinho no dev, e prod recebe um merge de `development`. Não há staging.

| Branch | Backend | Front |
| --- | --- | --- |
| `development` | API dev | web dev |
| `main` (no front: `master`) | API prod | web prod + atualização OTA nos celulares |

## Passo a passo

1. Crie uma branch a partir de `development` e acumule nela **todas** as mudanças da tarefa. Rode os testes.
2. Abra **um** PR para `development`. Só faça merge quando a tarefa estiver completa: cada merge é um deploy no dev, e o dev fica fora do ar alguns minutos a cada deploy. Nada de push direto em `development`.
3. O merge sobe no dev sozinho. Valide lá.
4. Release: PR `development` → `main` (front: `master`), mergeado com o ok do Alexandre. Nada de cherry-pick nem commit direto em `main`/`master`.
5. Se o front depende de mudança no backend, o backend vai pra prod antes.

```mermaid
flowchart LR
  A[Branch da tarefa: acumula tudo] --> B[Um PR para development]
  B --> C{Testes ok e revisado?}
  C -- não --> B
  C -- sim --> D[Merge em development]
  D --> E[Sobe no dev sozinho]
  E -- validado --> F[PR de release development → main/master]
  F -- ok do dono --> G[Prod: backend antes do front]
```

## Regras

- Um merge por tarefa, não por commit: vários merges seguidos derrubam o dev (cada deploy cancela o anterior).
- `git pull --rebase` antes de todo push.
- Cada um usa a própria conta GitHub e identidade git. Nada pessoal no `CLAUDE.md`: isso vai no `CLAUDE.local.md`, fora do git.
- Mudou algo nativo (lib, permissão, ícone)? Precisa de build de loja — ver `RELEASE.md` do front.
