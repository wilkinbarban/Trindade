# Calculadoras e Lançamentos Operacionais (v0.5.1)

Quatro ferramentas operacionais para a planta, disponíveis na web e no Android. As duas plataformas partilham a
**mesma regra de negócio**, escrita uma vez em cada linguagem num módulo puro com os seus testes, e o **mesmo
contrato**: o catálogo e os lançamentos chegam do servidor e não de uma cópia local em cada cliente.

## Lançamento de Massas (`/massas-launch` e Android)

Registo em tempo real da produção e carregamento de massas por operador:
- **Catálogo de 10 produtos de massas**: inputs por caixas inteiras com cálculo automático de unidades em tempo real.
- **Resumo Coletivo do Dia**: agrega totais acumulados de caixas e unidades e lista cada lançamento individual com hora e operador.
- **RBAC de Autor/Administrador**: botões de edição e exclusão de lançamentos ativos exclusivamente para o próprio autor do lançamento (`launch.user_id == currentUserId`) ou para o Administrador.
- **Polling pasivo em tempo real**: recarga periódica silenciosa a cada 15 segundos (`POLL_INTERVAL_MS = 15000L`) em segundo plano enquanto a tela estiver visível e sem edição ativa.
- **Compartilhamento WhatsApp**: formato limpo `🍝 *Lançamento de Massas — DD/MM/AAAA*` com lista de produtos contados e rodapé `*Lançado por:* Operador1, Operador2`, estritamente **sem linha de Total Geral**.
- **Histórico de Massas (`/massas-history`)**: consulta por data e auditoria de dias anteriores.
  - **Modo Leitura em Datas Passadas**: quando a data selecionada não for o dia de hoje (`date != saopauloToday()`), oculta a calculadora e os inputs de caixas, desativa ações de edição/exclusão de lançamentos e exibe badge de *Modo Leitura*.
  - **Suspensão de Live Polling**: recargas automáticas são desativadas em datas históricas para prevenir chamadas desnecessárias à API.
  - **Navegação Integrada**: botão "Voltar ao Histórico" e retorno via `BackHandler` ao inspecionar lançamentos históricos.
- **Modal de Ajuda Contextual (`ModuleHelpDialog`)**: botão de ajuda presente no topo explicando o fluxo em 3 passos simples.

## Calculadora de Assaí

Calcula um pedido de compra. A regra, em `packages/frontend/src/lib/assai.ts` (web) e em
`com.trindade.app.assai.AssaiOrder` (Android):

- Um pedido só existe em **caixas inteiras**: uma quantidade que não fecha caixas é recusada em vez de
  arredondada, porque arredondar produziria um número que não corresponde ao que chega ao portão.
- O aviso por produto aparece **depois de o operador sair do campo** e apaga-se ao reentrar para corrigir:
  enquanto se digita, uma quantidade está a ser escrita.
- A exportação sai com uma linha por produto e o total, sem a quantidade por caixa.
- **Seleção de dia da semana**: permite associar o pedido opcionalmente a *Terça-feira*, *Quinta-feira*,
  *Sexta-feira* ou *Sábado*, refletindo no cabeçalho `🛒 Pedido Assaí <Dia>`.

## Módulo de Compartilhamento (Compartilhar App)

Permite enviar os links oficiais de acesso ao Trindade Massas via WhatsApp e copiar para a área de transferência:
- **Plataformas**: Android (APK direto), iPhone / Web (navegador) e Ambos.
- **Credenciais oficiais (Administrador)**: listagem de usuários do sistema, preenchimento de usuário e senha
  inicial editável (padrão `123456789`). Operadores não-administradores não visualizam nem anexam credenciais.
- **Entrada telefônica padronizada**: máscara progressiva `(11) 9XXXX-XXXX` com normalização internacional automática
  com prefixo `55` para abertura da API do WhatsApp (`wa.me` / `api.whatsapp.com`).

## Calculador de Estoque

Conta caixas de dia. O catálogo (25 produtos, em `stock.catalog.ts` no backend) chega pelo endpoint
`GET /api/stock?date=YYYY-MM-DD`, que devolve **todos** os produtos —a zero os que ninguém contou— para que a
pantalla não tenha de os inventar.

- As caixas acumulam e as **unidades são recalculadas a partir delas em cada escrita**, de modo que os dois
  números não podem discordar.
- Cada entrada e cada reset ficam no **registo** da linha, com o total corrido depois de cada uma: por isso um
  reset zera os totais sem apagar o que já se tinha contado.
- Um dia que não se pôde ler é **nada** e não um dia vazio: mostrar zero caixas para um servidor inalcançável é
  mentir sobre a estante.

## Histórico de Estoque

Uma rota própria na web (`/stock-history`, com a antiga `/stock/history` a redirecionar) e uma pantalla no
Android. Lista **só os produtos contados** —vinte e quatro zeros escondem a linha por que alguém veio— e tem
**controlo de papel**: um Trabalhador lê, um Administrador corrige, e o servidor aplica a mesma fronteira com o
seu 403 (`POST /api/stock/add-entry` e `POST /api/stock/reset-product` são do Administrador).

## Exportações e destinos

- **WhatsApp**: pela ligação canónica `https://api.whatsapp.com/send?phone=...&text=...`, com a folha de
  partilha do sistema e a área de transferência como reserva.
- **Correio do stock**: vai para a planta, `xmitrindade@hotmail.com`.
- **Marca de água do autor**: «Desenvolvido por Wilkin Barban Rosabal» e «E-mail: wilkin.barban@gmail.com»,
  em 10sp, no tom atenuado do tema e centrada ao pé das pantallas principais. É outra coisa que o correio do
  stock e usa a morada do autor.

## Esquema da base de dados

A revisão **5** acrescenta `stock_counts` (aditiva, sem reescrever nada), com `UNIQUE(count_date, product_code)`
e o catálogo em código: cada linha guarda o nome e as unidades por caixa **do momento em que foi contada**,
para que uma mudança futura do catálogo não reescreva uma contagem antiga. A migração corre com cópia de
segurança preventiva verificada antes de tocar na base.

## Verificação no Android

```bash
# Os testes de unidade do cliente, no host onde vive o SDK
./gradlew :app:testDebugUnitTest --tests "com.trindade.app.massas.*"
./gradlew :app:testDebugUnitTest --tests "com.trindade.app.ui.ModuleHelpTest"
./gradlew :app:testDebugUnitTest --tests "com.trindade.app.stock.*"
./gradlew :app:testDebugUnitTest --tests "com.trindade.app.assai.*"

# O emulador: o APK de debug é o que Android assina sozinho
./gradlew :app:assembleDebug -PversionName=0.5.1 -PversionCode=502
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb exec-out screencap -p > pantalla.png
```

O APK de **release** exige três parâmetros e não tem valores por defeito, de propósito:

```bash
./gradlew :app:assembleRelease --no-daemon \
  -PapiBaseUrl=https://trindademasas.duckdns.org/ \
  -PversionName=0.5.1 -PversionCode=502
```

Um release não pode apontar em silêncio para o loopback de desenvolvimento nem sair com uma versão que o build
inventou; o `versionCode` sai da fórmula do próprio carril de release (`major*10000 + minor*100 + patch`), e o
artefacto local sai **`unsigned`**: a assinatura é um passo do carril do CI com o keystore.
