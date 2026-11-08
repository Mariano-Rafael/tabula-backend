# Plano inicial do frontend

## Stack acordada como diretriz

React + TypeScript strict + Vite, SPA; React Router; TanStack Query; Zustand para estado compartilhado do editor; Tailwind CSS + shadcn/ui; React Hook Form + Zod; `@xyflow/react`; Fetch encapsulado; Vitest e Playwright conforme o trabalho implementado.

TanStack Query mantém dados remotos/cache/mutations. Estado local ainda não salvo do diagrama, seleção e alterações da sessão de edição podem usar Zustand. Componentes/formulários simples podem manter estado local. Não é necessário instalar todas as dependências ou iniciar o editor na primeira etapa.

Use versões estáveis compatíveis verificadas ao iniciar, um gerenciador e um lockfile. A stack foi recomendada para painel autenticado e editor consumindo a API Java existente; não é necessário criar outro backend no frontend.

## Estrutura

```text
src/
  app/                  # Rotas, providers, layout e inicialização
  shared/
    api/                # Fetch, CSRF, erros, tipos e headers
    ui/                 # Componentes reutilizáveis
  features/
    auth/
    portfolios/
    projects/
    integrations/
    flows/
    editor/
    conversations/
```

No repositório novo, o agente pode criar o projeto com o template React/TypeScript do Vite e incorporar os arquivos de handoff existentes. Não sobrescreva AGENTS/documentação ao inicializar em diretório já preenchido.

## Desenvolvimento local

Frontend em `http://localhost:5173`; backend em `http://localhost:8080`. Acesse `/api/v1` pela origem do frontend e configure o proxy Vite. Exemplo para adaptar ao `vite.config.ts` criado pelo template:

```ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
});
```

`.env.local` do frontend contém `VITE_API_BASE_URL=/api/v1`. Não repita `/api/v1` duas vezes ao montar URLs. O backend deve rodar pela raiz de seu próprio repositório, com MySQL/MongoDB/configuração própria.

Para **HTTP local**, configure `SESSION_COOKIE_SECURE=false` no ambiente do backend. A configuração padrão é cookie Secure; isso deve permanecer habilitado em HTTPS. A variável é do backend e não deve virar `VITE_SESSION_COOKIE_SECURE`.

Não há configuração CORS explícita encontrada no backend desta fotografia. O proxy evita requisições cross-origin do navegador. Em produção, prefira origem única com `/api` encaminhado por proxy reverso e fallback da SPA para as demais rotas. Se for necessário separar origens, será preciso configurar CORS/credenciais/cookies e exposição de ETag/Location/Retry-After no backend; não desabilitar CSRF para contornar isso.

## Primeira entrega

1. Aplicação inicial com typecheck/build, estilo base e rotas.
2. Cliente HTTP com cookies, CSRF, leitura de ProblemDetail e preservação de headers.
3. Login real, restauração da sessão por `/auth/me`, área protegida e logout.
4. Layout da aplicação e listagem/criação de portfólios reais.

Critérios: ao atualizar a página a sessão é restaurada; credenciais inválidas exibem erro; rota protegida aguarda a checagem de sessão; logout limpa dados locais de todos os escopos; não há armazenamento de senha/token de sessão no localStorage.

Depois: projetos → vínculo IBM isolado e probe → destino com IDs informados → importação → versões/rascunho → diagrama/layout. Prefira entregar cada fluxo vertical conectado à API.

## Primeira versão do editor

Registrar componentes `ibmDialog`, `ibmAction` e grupos; renderizar relações com significado explícito; exibir diagnósticos e limitações; movimentar/organizar localmente e salvar somente o contrato de layout.

Não inserir criação/exclusão/publicação completa como promessa da primeira entrega. O usuário adiou o tradutor para depois da experiência inicial do frontend. Mantenha a arquitetura preparada para alterações locais e salvamento explícito, sem implementar um endpoint de lote fictício.
