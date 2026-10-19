# Changelog (pt-BR)

English (padrão): [CHANGELOG.md](CHANGELOG.md)

Todas as mudanças importantes do REau ficam aqui, da mais nova para a mais antiga, no formato do
[Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/). O app segue o versionamento semântico na forma `0.y.z`
(ainda sem 1.0, então qualquer coisa pode mudar entre versões menores): as builds de release se chamam `0.1.0`, e as builds
de debug acrescentam a data e a hora da compilação depois de `-dev+` (`0.1.0-dev+AA.MM.DD-HH.MM`, por exemplo
`0.1.0-dev+26.10.19-09.37`). As versões aparecem da mais nova para a mais antiga; os dias anteriores à 0.1.0 ficam abaixo
dela como histórico do projeto, cada um reunindo o que mudou naquele dia (dias sem mudança visível para quem usa o app não
aparecem).

## [0.1.0] - 2026-10-19

### Adicionado
- **Primeira versão publicada.** O REau leva o Android Auto do celular, com o Waze, para o painel Tripper Dash da Royal
  Enfield, pelo próprio Wi-Fi do painel.
- **Duas edições**, que se instalam lado a lado: a **Lite** (recomendada; sem serviço de Acessibilidade, você liga o
  servidor de unidade principal do Android Auto na mão) e a **Completa** (um serviço de Acessibilidade liga o servidor
  sozinho).
- **Chamadas pelo joystick**: atender, recusar e desligar pelos botões do painel.
- **Dados da moto** mostrados no app, como o painel os informa.
- **Inglês e português**, seguindo o idioma do celular ou escolhidos nos Ajustes.
- **Sem conta e sem coleta de dados**: tudo fica no celular.

## 2026-10-19

### Alterado
- A **documentação** segue a regra do changelog: `README.md` e `TERMS_OF_USE.md` são os textos em inglês e os em português são `README.pt-br.md` e `TERMS_OF_USE.pt-br.md` (este era `TERMOS_DE_USO.md`). O README agora diz que o registro de diagnóstico vem ligado por padrão, que há um APK publicado e como liberar as configurações restritas (Android 13 ou mais novo); diz "a tela do painel" onde dizia "vidro", deixa o endereço do código-fonte do REau para a tela de Créditos e tira o parágrafo sobre o modo experimental do Android Auto 17.3 ou mais antigo; e aponta o changelog. O README passa a dizer que a **Lite é a edição recomendada** (sem serviço de Acessibilidade; o custo é ligar à mão o servidor do Android Auto a cada uso).
- Os **termos de uso** chamam o recurso do navegador pelo nome que o app usa ("Ligar o servidor de rede", antes "Ver no navegador") e, em inglês, citam o botão "I accept" como ele aparece no app e dizem "is off by default" onde diziam "comes off"; descrevem o projeto como criado por alguém que gosta de moto e é entusiasta de software livre; e passam a dizer que a compatibilidade foi verificada com o firmware 13.10, o que o painel em uso informa (diziam 11.63). A versão dos termos continua 2.
- Os **Créditos** e o `NOTICE` agora apontam o open-dash para o projeto de origem (`subtlesayak/open-dash`) e nomeiam o titular, "Subtle Sayak e colaboradores": o fork que eles citavam saiu do ar, e a sequência de saída do painel creditada ali vem de uma versão publicada antes da limpeza que o projeto fez em 21/09/2026.

### Corrigido
- O atalho **Clima** deixa de ficar em "Buscando…" para sempre quando não há posição, internet ou resposta do serviço: agora diz o motivo, o mesmo que a tela dele diz.
- O aviso **"Android Auto não responde"** da edição completa ganha o botão **Abrir Acessibilidade** quando o serviço está desligado (o texto já mandava ativá-lo, mas não havia caminho).
- O aviso de que o **servidor do Android Auto ficou ligado** deixa de culpar só o celular bloqueado: na edição completa ele também cita a Acessibilidade do REau desligada (a Lite mantém o texto sem a Acessibilidade).
- **Desligar** com o painel conectado deixa de registrar, no registro de diagnóstico, um falso aviso de que o socket do painel falhou.
- **Desligar** deixa de registrar um erro do codificador de vídeo ("Pending dequeue output buffer request cancelled"): o decodificador agora espera a thread de saída parar antes de se encerrar.
- Sair da **Tela do Android Auto** deixa de registrar um aviso de que a prévia do celular falhou: ela só desliga, como sempre, e o painel segue.
- Um **tranco raro na imagem do painel** foi corrigido: quando o Android Auto cortava um quadro de vídeo de modo que o último pedaço tinha um único byte, o REau o descartava, o quadro saía quebrado e o REau pedia uma imagem nova ao Android Auto (uma parada breve da imagem).

## 2026-10-18

### Adicionado
- A tela **Dados da moto** ganha a seção **Autonomia**: quanto a moto ainda anda com o combustível que tem, do jeito que o próprio painel conta (o código `0C07`, que ele manda a cada uns 5 segundos), com a hora em que foi lida. Aparece em quilômetros, ou em milhas com os quilômetros entre parênteses se o painel estiver em milhas; sem leitura, diz "Ainda não disse" e não mostra número nenhum.
- Os **termos de uso** só mostram "Li e aceito" e "Não aceito" depois de lidos até o fim: antes disso o rodapé tem o botão "Descer até o fim", que faz o texto rolar de forma contínua, em ritmo de leitura (1 segundo), até o fim; tocar na tela interrompe a rolagem (ou basta rolar o texto até lá).
- O ícone do **REau Lite** ganha a palavra "Lite" escrita por cima, em branco com borda no cinza do fundo do app, no canto inferior direito, para distinguir do REau na lista de apps (também no ícone temático).

### Alterado
- Os **termos de uso** passam à **versão 2** (quem tinha aceitado a 1 aceita de novo): a frase sobre o protocolo do painel diz que o fabricante não o documenta nem apoia e que ele foi reconstruído por projetos abertos da comunidade e por testes de compatibilidade, verificada com o firmware 11.63.
- O aviso **"O REau e os seus dados"** passa a dizer tudo o que fica no celular: além do registro e das gravações, o nome e a senha do Wi-Fi do painel e o que o painel já informou (cifrados com uma chave guardada pelo Android) e, se você ligar, a posição arredondada e o último clima.
- O `NOTICE` e a tela de **Créditos** passam a dizer, projeto por projeto, o que veio de cada um (better-dash, NorthStar, recon-dash, open-dash e a linhagem do headunit) e que marcas citadas pertencem aos seus donos.
- Os textos que falavam da senha de fábrica do painel passam a dizer só "senha padrão do painel", sem o número, e a ajuda do Wi-Fi do painel avisa: havendo mais de um painel por perto, confira o nome e use **Esquecer**.

### Corrigido
- **Primeira conexão ao Wi-Fi do painel.** Ela deixa de se perder na janela de 30 segundos: na primeira vez que o REau pede essa rede, o Android pergunta, num diálogo dele ("Conectar ao dispositivo"), se pode conectar, e se o toque em **Conectar** demorava mais que o pedido, o Android mostrava "Algo deu errado" e recomeçava (uns 5 minutos numa instalação nova). Enquanto o REau nunca entrou nessa rede neste celular, o pedido agora espera até **2 minutos**, e a tela e a notificação dizem "O Android pode perguntar se o REau pode conectar ao painel: toque em Conectar"; depois que a rede entrou uma vez (lembrado junto com os dados do painel, apagado por **Esquecer**), voltam as esperas de antes (30 segundos na primeira tentativa de cada ligação, 10 nas reconexões). Esse aviso só aparece quando a última busca de redes do celular vê o painel, e o REau só pede a rede ao Android depois de ver o painel por perto; assim, com a moto desligada ou longe, o diálogo "Conectar ao dispositivo" não aparece mais repetidas vezes. O registro do passeio também diz quando cada pedido foi solto, a idade da última busca e se a rede já estava aprovada.
- **Desligar e ligar de novo.** Entre o toque em **Desligar** e o fim do desligamento, a tela e a notificação dizem **"Desligando…"** e o botão Ligar fica desabilitado, em vez de parecer morto; volta a "Parado" mesmo se algum passo do desligamento falhar. Tocar em **Ligar** logo depois não manda mais "forçar a parada" do Android Auto: ele leva até cerca de 1 minuto para fechar a sessão anterior e, nesse tempo, aceita a conexão sem responder. Por até 2 minutos o aviso diz que o Android Auto está fechando a sessão anterior, que o REau tenta de novo sozinho e que, para não esperar, dá para parar e iniciar de novo o servidor da unidade principal nas configurações dele. O aviso deixa de alternar com "Conectando" a cada rodada, e os 2 minutos também valem se o REau for fechado e aberto de novo nesse intervalo. Nesse meio tempo a edição completa não para e inicia o servidor da unidade principal por conta própria (isso podia derrubar o Android Auto com o erro "Already connected"); só se o prazo passar com o Android Auto ainda mudo é que ela tenta, uma vez, o reinício de antes.
- **Servidor do Android Auto mudo e duas edições instaladas.** Quando o Android Auto aceita a conexão e fica mudo, o REau não gasta mais o único reinício do servidor enquanto o serviço de Acessibilidade ainda não conectou: ao conectar (e se o servidor continua mudo), a edição completa faz o reinício sozinha. Na **Lite**, e na completa sem o serviço ligado, o aviso, a linha sob o título e a notificação mandam parar e iniciar o servidor da unidade principal no menu ⋮ do Android Auto, em vez de "Forçar parada". Com as duas edições instaladas, a tela avisa que a outra edição pode estar usando o Android Auto e pede para desligá-la, o "Android Auto não responde" não pisca mais para "Conectando" a cada tentativa, e uma sessão que termina por fora do REau (a da outra edição) aparece como "Fechando a sessão anterior" por até 2 minutos. A espera de 120 s por uma sessão anterior que não fecha continua, e o registro diz quando ela acaba.
- **Textos de Lite e Acessibilidade.** Na **Lite**, os termos de uso não falam mais do serviço de Acessibilidade (ela não o tem), e o aviso "Servidor do Android Auto desligado" deixa de falar de Acessibilidade e de mostrar o botão "Abrir Acessibilidade": diz só o passo manual (opções de desenvolvedor do Android Auto, menu ⋮, "Iniciar servidor da unidade principal"), que o servidor desliga quando a sessão termina e que é preciso ligá-lo de novo a cada vez, e mantém o botão que abre as configurações do Android Auto. Na edição completa, o pedido para ativar o REau em Acessibilidade aparece uma vez só, e não mais duas frases quase iguais (nenhuma com o serviço já conectado), e depois de ativá-lo e voltar ao app o REau tenta ligar o servidor sozinho assim que o serviço conecta, se o aviso estava na tela por causa dele.
- **Outros avisos e contagens.** "Android Auto sem imagem" (e o conselho de forçar a parada do Android Auto) só aparece com o painel conectado; com a moto ou o painel desligado o REau segue em "Conectando", sem alarme. Enquanto o painel nunca conectou na ligação, o REau diz "Procurando o painel" em vez de "Painel perdido, tentando de novo" ("perdido" só aparece depois de uma conexão que de fato existiu). Sem o Android Auto instalado, os botões "Abrir as configurações do Android Auto" dizem isso e levam à página dele na Google Play (ou na web, sem loja), e se nada abrir aparece um aviso em vez de silêncio. Em **Permissões**, o "Faltam N" e o "Pedir as que faltam (N)" só contam as permissões das funções ligadas nos ajustes; as das funções desligadas continuam na lista, marcadas como opcionais.
- O leitor do **cabeçalho do vídeo** (SPS do H.264) passa a achar o tamanho certo quando o cabeçalho traz listas de escala (perfis altos) ou `pic_order_cnt_type` 1, que antes davam um tamanho errado; cabeçalho vazio ou cortado antes do tamanho vira "sem dado" em vez de um tamanho inventado.

## 2026-10-17

### Adicionado
- **Duas edições do REau**, que instalam juntas no mesmo celular (mas não devem rodar ao mesmo tempo). A **completa** (`com.reau.app`) é a de sempre. A **Lite** (`com.reau.app.lite`, "REau Lite") não tem o serviço de acessibilidade, nem o encaixe próprio do menu de apps, que depende dele: com o Android Auto 17.4 ou mais novo, quem liga o servidor da unidade principal é você, à mão; com o 17.3 ou mais velho, o REau pede ao Android Auto que ligue para ele (experimental, provado só até a abertura da sessão) e avisa "O Android Auto não ligou para o REau" se ele não ligar. `./gradlew assembleRelease` gera os dois APKs.

### Alterado
- Os **Termos de uso** dizem que só a edição completa usa o serviço de acessibilidade (a Lite não usa).
- No perfil reduzido da navegação, o vídeo do painel também baixa para 200 kbps (junto com os 2 quadros por segundo) e
  volta a 400 kbps quando o perfil volta ao normal.
- **Estado do celular**, **Capa do álbum**, **Rota real no painel** e **Clima no painel** passam a valer na hora, com a projeção ligada (as três primeiras ganham o selo NA HORA); antes só valiam na próxima vez que ligava.
- **Aviso de imagem parada:** com a moto andando numa rota e o Android Auto sem mandar nenhuma imagem nova por 15 segundos, o painel mostra o selo **IMAGEM PARADA** embaixo e a notificação e a tela principal passam a dizer "Sem imagem nova" (antes o painel repetia a última imagem e parecia vivo). Some assim que a imagem volta. Parada ou sem rota não há aviso, porque aí o Android Auto fica quieto de verdade.
- A tela **Créditos** passa a mostrar, no alto, o endereço do código-fonte do próprio REau (um toque copia), como a licença AGPL pede; o ano da fonte Fraunces passa a ser o que a própria fonte traz (2020).
- O relógio do sol (que decide o tema claro ou escuro pelo nascer e pôr do sol) foi reescrito a partir das equações públicas da NOAA, com testes de referência astronômica; o `NOTICE` ganhou os arquivos que faltavam e perdeu um que não existe mais.
- **Usar a localização** também vale na hora: com o tema pelo sol e a projeção ligada, o painel assume o dia ou a noite do lugar logo ao ligar ou desligar a chave (ganha o selo NA HORA); antes levava até 1 minuto.
- Os **Termos de uso** e os **Créditos** passam dos Ajustes para o fim da tela "O REau e os seus dados", junto do que o app é e do que ele faz com os seus dados.
- Os textos de ajuda (o botão **i**) foram revisados em todas as telas: ganharam ajuda os perfis de vídeo, o wallpaper, as
  gravações de depuração, o modo de encaixe, as margens, o painel conhecido e a última leitura dos dados da moto, e
  vários textos foram corrigidos ou completados.

### Removido
- Os textos de ajuda de "Música tocando" e "Quem está ligando", que só repetiam a linha de baixo (a linha agora diz mais).

### Corrigido
- Quando o Wi-Fi da moto volta depois de ter sumido, o painel passa a voltar na hora, em vez de esperar até 1 minuto
  pela próxima tentativa.
- O resumo do passeio no registro conta como "queda do painel" só o painel que estava ligado e se foi; as tentativas de
  reconexão que falham aparecem à parte, em `retries dash`.
- Ao trocar o idioma nos Ajustes a tela deixa de piscar: os textos mudam na hora, sem recriar a tela (Android 13 ou mais novo).
- O selo **NA HORA** dos Ajustes passa a aparecer só nas opções que valem de verdade com a projeção ligada: idioma, tema,
  atender ligação, registro de diagnóstico, registro detalhado e apagar sozinho os mais velhos. Os textos de ajuda de
  "Rota real no painel", "Usar a localização" e "Quando cada ajuste vale" passam a dizer quando cada uma vale.

## 2026-10-16

### Adicionado
- **Primeira versão apresentada ao público** (lançada como 0.1.0 em 2026-10-19). O REau leva o Android Auto do celular, com o Waze, para o painel Tripper
  Dash da Royal Enfield, pela rede Wi-Fi do painel e com a tela do celular apagada.
- **Chamadas pelo joystick** (chave "Atender ligação"), nos dois modos do painel e para ligação de operadora ou de
  aplicativo de voz: tocando, a esquerda atende e a direita ou o centro recusam; em ligação, o centro desliga e
  cima e baixo mudam o volume. Atender e recusar agora funcionam já no primeiro aperto da tela de chamada do painel.
  A notificação "Android Auto no painel" e os Ajustes passam a dizer que o centro desliga a chamada.
- **Inglês** como idioma do app, além do português: o app segue o idioma do celular, e há um seletor Automático /
  Português / English nos Ajustes. O inglês é o padrão para quem usa outro idioma.
- **Aba "Chamada"** na página do joystick, só para leitura, com o que cada botão faz tocando e em ligação.
- **Aviso "Android Auto sem imagem"** quando a conexão abre mas o Android Auto não manda nenhum quadro por 10 s
  (o codificador do próprio Android Auto travado); o aviso orienta a tentar de novo.
- **Termos de uso**, em português e em inglês, com aceite na primeira abertura e a mesma cópia em
  `TERMOS_DE_USO.md` (hoje `TERMS_OF_USE.pt-br.md`).
- **Registro** passa a anotar a distância das mensagens de posição do Android Auto, para esclarecer passeios com rota e
  sem velocidade.

### Alterado
- Depois de uma ligação atendida ou encerrada, o mesmo botão fica mudo por 3 s, para que um toque repetido sem querer
  não mova o foco do Android Auto; a pausa vale só para o controle que acabou de agir.
- O servidor do Android Auto é reiniciado já na primeira falha depois de uma instalação ou de uma parada forçada,
  em vez de esperar cerca de 33 s.

### Corrigido
- Um painel que responde mas parou de mandar a confirmação do primeiro quadro, depois de cair no meio de uma
  chamada, era derrubado a cada 15 s.

### Segurança
- Sem conta, anúncio nem coleta de dados. A única conversa com a internet é a previsão do tempo, opcional e
  desligada de fábrica.

## 2026-10-15

### Adicionado
- **Rota real no painel** (atrás de uma chave, desligada de fábrica): tempo restante, curva depois da próxima e ícone de
  sem sinal.
- **Volume da chamada pelo joystick**: cima e baixo mudam o volume enquanto a ligação está em andamento.
- Tela **"Android Auto na rede"**, que reúne o servidor de rede, o navegador e o som; na tela inicial aparece um
  ícone com a janela de dados e um botão de parar. Seção "Depuração" nos Ajustes, com as opções experimentais em
  vermelho.
- A hora de chegada aparece no cartão do painel, a barra do anel de manobras fica igual à do aplicativo e a
  velocidade estimada deixa de dar picos. O registro passa a anotar o estado da chamada em cada botão e a rota do
  áudio.

### Alterado
- **Com o servidor de rede ligado, o REau não procura o painel**; o servidor só liga depois do "Ligar" e desliga a
  cada começo e fim de projeção. O servidor do navegador nunca sobe sozinho: começa desligado em cada projeção.
- Os perfis de vídeo ficam um mínimo de 10 s num nível antes de trocar, e o encaixe próprio do menu de apps lembra o
  que era antes. O tempo restante passa a bater com o do Waze e a velocidade estimada vale por 8 s, sem falsa
  velocidade quando o quilômetro salta.
- Os textos dos botões (i) e de duas linhas da Tela do Android Auto ficaram mais curtos, e a "Redução da imagem" fica
  fixa em Padrão.

### Removido
- As opções "Android Auto mais devagar" e "Descartar o início", e a escolha da "Redução da imagem" (veja acima).

### Corrigido
- Acentos no título da música e no nome do aparelho apareciam errados no painel.
- O perfil de vídeo trocava a cada 10 s na estrada, e escolher um item do menu de apps pelo joystick agora
  volta na hora ao encaixe dos apps.
- A gravação do painel, para diagnóstico, passa a guardar até 3000 quadros.

## 2026-10-14

### Adicionado
- **Servidor de rede** (opcional, desligado de fábrica): mostra o Android Auto numa página da rede local, com toque,
  protegida por PIN de 4 dígitos com trava contra palpites e teclado numérico; desliga sozinha ao fim da projeção.
  Há uma seção "Navegador" na Tela do Android Auto.
- **Som do Android Auto no navegador**, no celular ou nos dois, com chave de três modos; inclui a voz do Gemini e do
  Waze. O som vai a todos os navegadores conectados, e um navegador aberto no próprio celular não recebe som (evita
  um laço de captura). Atender chamada pelo navegador, tocando no cartão do Android Auto.
- **Registro mais completo**: tamanho do quadro-chave, filtro de redução em uso, linha dos ajustes em uso, motivo do
  fim do link, vídeo detalhado a uma linha por segundo e níveis de aviso e erro; nenhuma senha vai ao registro.
- **Dados da moto** passa a perguntar ao painel ao conectar e a cada 15 minutos.
- **Créditos** refeitos, com o ícone do REau, bibliotecas e fontes por criador e crédito do serviço de previsão do
  tempo também na tela de Clima.

### Alterado
- O **protocolo 1.7 do Android Auto** passa a ser o padrão (traz o quilômetro total da viagem); a chave de FPS e bitrate
  vai para a Tela do Android Auto.
- Acabamento dos Ajustes: tema "Claro/Escuro", "Tirar o foco azul" passa a valer sempre, "Perfil A" e "Perfil B", e
  "Cartela" vira "Wallpaper" ("Wallpaper de teste", "Mandar o wallpaper", "Voltar ao padrão").
- Barra de cima com a mesma altura em todas as telas; "O REau e os seus dados" e Permissões com textos mais curtos e
  ordem por importância. O registro acompanha cada evento em uma linha só, sem linhas repetidas.

### Removido
- Alguns textos dos Ajustes, e a lista de ações de ligação e as quatro setas da página do Joystick.

### Corrigido
- O contraste do botão da máscara no tema claro, e o navegador mostrava imagem parada quando nada mudava
  na tela do Android Auto (passou a redesenhar).
- Ligar a chave do som não reinicia mais o Android Auto, um engasgo no som não derruba mais o navegador, e o
  som do Android Auto deixou de ser recapturado do próprio REau em um laço.

## 2026-10-13

### Adicionado
- **Dados da moto**: uma tela só de leitura com o que o painel informa (versão, identificação escondida até "Mostrar",
  ajustes do painel, ABS e combustível, códigos ainda sem nome).
- **Clima no painel** (opcional, desligado de fábrica): a previsão é buscada ao abrir o REau, com 8 horas e 8 dias, em
  tela própria com ícones coloridos e horas em lista. Sem clima, o painel não mostra temperatura inventada.
- **Dois perfis de vídeo fixos**: 4 fps com 400 kbps e 2 fps com 200 kbps, trocados sem refazer o codificador e
  segurando o último nível; grupo "Perfis de vídeo" nos Ajustes.
- Confirmação antes de abrir a página de Acessibilidade do Android (que traz avisos de aplicativos bancários).
- Velocidade exibida no painel, relatório da qualidade do link e reconexão do Wi-Fi do painel.

### Alterado
- Wi-Fi do painel mais simples: nome e senha guardados atrás de uma linha.
- A tela Dados da moto fica mais enxuta: firmware só com a hora e estado só "Ligado ou Desligado".
  "Manobra e distância" fica fixa e ligada.

### Removido
- Os testes do painel na tela Dados da moto, e os campos para o começo do nome e para a senha do Wi-Fi.

### Corrigido
- Um quadro-chave sem os parâmetros SPS e PPS podia ser enviado ao painel; agora não é.
- Textos do clima que quebravam ou encostavam nas bordas, e o serviço de acessibilidade é lido de novo
  quando conecta.

## 2026-10-11

### Adicionado
- **Texto de FPS e bitrate sobre o vídeo** (chave em Avançado).
- **Perfis de qualidade por navegação**: por manobra, por velocidade e a regra de qual vence.

### Removido
- O atalho "Wi-Fi da moto" e o balão sem rota, para deixar os Ajustes mais enxutos.

## 2026-10-10

### Adicionado
- **Novo visual "Aurum"**: tema, fontes e ícone novos, tela Registro, notificação e capa redesenhadas, tema claro e
  **tema pelo sol** (automático pelo nascer e pôr do sol).
- **Wi-Fi do painel achado e preenchido sozinho**, usando a localização apenas para listar as redes; o nome fica
  escondido por padrão e há aviso quando uma VPN com divisão de túnel bloqueia a rede da moto.
- Tela inicial com a versão do REau, Wi-Fi em tela própria, wallpaper escolhido pelo nome e sem rolagem.
- Quando o modo desenvolvedor do Android Auto está desligado, o botão leva às configurações do Android Auto.
- Velocidade e Wi-Fi na linha de transmissão do registro.

### Alterado
- **Vídeo conservador como padrão**: 4 fps, 200 kbps, taxa constante e quadro-chave a cada 1 s, para o painel parecer
  mais rápido.
- O título no painel é sempre "REau"; a notificação deixa de mostrar o nome do app.
- Servidor do Android Auto: a capa aparece no toque, sem os 2 s ao desligar, e um receptor parado não espera para
  discar de novo.

### Removido
- A permissão "Dispositivos por perto", que não tinha uso.

### Corrigido
- Em instalação limpa no Android 13 ou mais novo, a acessibilidade ficava restrita e o Wi-Fi da moto não
  recebia o nome inteiro.

## 2026-10-09

### Adicionado
- **Páginas de permissões** (com o que cada uma faz e botões para pedir as que faltam), **"O REau e os seus dados"**
  (com atalho para desligar e apagar o registro, que também pode ser visto dentro do app) e **créditos** dos projetos
  e bibliotecas usados; a capa e o aviso passam a dizer "desligando".
- **Cartão de música no formato do painel**, com álbum, título e artista, e o nome de quem liga segue protegido
  junto com o estado da ligação.
- Aviso "Battery low" uma vez por descida de nível, com o nível e a marca, e aviso de painel travado, que pede para
  desligar a moto; a queda pede a rede uma vez só.
- O foco azul do Android Auto, deixado por um toque cancelado, some um minuto depois do último uso do joystick.

### Alterado
- **Privacidade:** a localização fica desligada por padrão; a acessibilidade só ouve WhatsApp, Telegram e Signal, e só
  com a chave das ligações ligada; registro e gravações ficam no armazenamento interno do app e são apagados sozinhos
  após 30 dias. O nome do Wi-Fi do painel é mascarado em todas as linhas do registro.
- O modo noite deixa de depender da leitura da posição, que nunca chegava em segundo plano.
- O anel de manobras fica escondido no modo mídia; ícones de zoom e distância total só aparecem quando existem; o
  título ao lado do mapa diz o app que está sendo mostrado.
- O decodificador do Android Auto passa a ter thread própria, reduzindo a leitura presa de 81 ms para 3,6 ms por janela
  nos testes.
- Começa o novo visual: tema, componentes, tela principal, Ajustes, Tela do Android Auto, Joystick, Permissões, dados e
  Créditos.

### Corrigido
- O anel de manobras sumia e não voltava no modo mídia.

## 2026-10-08

### Adicionado
- **Música e quem liga nos cartões do próprio painel**, a partir dos canais de mídia e de telefone do Android Auto,
  sem permissão nova.
- **Ligação pelo joystick também pelo Android Auto**, com as teclas do volante, o que faz a chamada de WhatsApp
  funcionar.
- O encaixe passa a seguir a tela do Android Auto mesmo sem aviso do sistema.

### Alterado
- O joystick é tratado direto na recepção, e o tráfego do Wi-Fi ganha prioridade de rede; o vigia do painel cai de 6
  para 4 s. Menos uso de processador e memória no registro, na recepção de rede e na leitura de pacotes.
- O registro fica mais fiel: um fim pedido não conta como queda, e autenticação sem resposta não culpa o nome do Wi-Fi.

### Corrigido
- O anel de manobras sumia num trecho sem manobra, porque o Waze manda eventos "desconhecidos" a cada 2 a 3
  s no meio da rota.
- O encaixe do menu ficava preso no Waze em algumas trocas de tela.

## 2026-10-07

### Adicionado
- **Menu de apps do Android Auto detectado** pela acessibilidade, com encaixe próprio trocado sozinho e DPI ajustável
  na Tela do Android Auto; o visor do Google Maps também é reconhecido.
- Ligação: a esquerda atende e a direita recusa na tela de chamada; chamadas de apps de voz passam a ser lidas pelo
  que a notificação traz.
- Pedido de isenção de economia de bateria, com aviso quando ela está ligada.
- Máscara do painel na Tela do Android Auto, que se liga e desliga.
- Recorte da imagem além de 0% e 100%, e depois em pixels (de 1 em 1 e de 10 em 10), até a janela inteira ficar fora
  da imagem; o encaixe na cúpula passa dos Ajustes para a Tela do Android Auto.

### Alterado
- O texto da chave de ligação fala em toque, não em segurar.
- O fim de uma projeção antiga não desliga mais o servidor da nova; ao fechar, o REau espera a resposta do Android Auto
  antes de encerrar.

### Corrigido
- A rotatória do Waze aparecia no sentido errado; agora é anti-horária com a mão direita.

## 2026-10-06

### Adicionado
- **Servidor do Android Auto ligado e desligado sozinho** pelo serviço de acessibilidade, com cobertura sobre a tela
  durante a manobra; a caminhada de ligar leva de 2,4 a 2,8 s.
- **Ping do Android Auto a cada 5 s**, que acaba com o "congelamento" de 15 s.

### Alterado
- Joystick: um toque, uma ação, e as ligações usam os mesmos lados.
- Uma rajada de mudanças nos Ajustes abre uma única conexão nova do Android Auto, 1 s depois da última.
- O estado "não responde" aparece depois de 3 apertos de mão sem resposta; religar espera 1 s depois do desligar; o
  repique do botão do joystick é filtrado.

### Removido
- O toque duplo do joystick.

### Corrigido
- No modo mapa sem rota o painel parava de repassar os lados do joystick, porque o REau escondia o balão;
  agora o cartão sem rota mostra o alfinete de chegada e a direita funciona.
- O leitor do registro só diz "conectado" depois que a negociação foi respondida.

## 2026-10-05

### Adicionado
- **Gemini pelo joystick**: cima duas vezes no modo mídia, ouvindo pelo fone ou pelo celular (atrás de chave; pede o
  microfone).
- **Página do joystick nos Ajustes**: modo mídia e modo mapa, com o que fazer em 1 toque, 2 toques e segurando.
- A linha de início do registro diz qual APK estava rodando.

### Alterado
- Os controles de vídeo passam a valer com a projeção ligada, sem religar.
- O painel sumido é procurado por 5 min a cada meio minuto e depois uma vez por minuto; a rede da moto que o celular
  já conectou passa a ser vigiada.

### Corrigido
- Desligar e religar em seguida tirava a projeção do primeiro plano, o que bloqueava a rede em segundo
  plano e matava o REau.

## 2026-10-04

### Adicionado
- **Joystick do painel ligado ao Android Auto**: roda, setas, confirmar, voltar e início. No modo mídia, a roda troca a
  faixa e o meio confirma; para baixo abre o menu de apps.
- **Ligações pelo joystick** (chave nova, com as permissões de atender e de estado do telefone): atender ou recusar
  pelo lado da tela de chamada.
- **Tela do Android Auto no celular** para ver a projeção, com atalho para as configurações do Android Auto e opção de
  tocar nela pela tela do celular (atrás de chave), também sem o painel conectado.
- **Registro mais completo**: um arquivo enquanto o app viver, logcat próprio, linhas do aparelho e do sistema,
  contagens do Android Auto e do painel, registro detalhado atrás de chave, botão "Marcar" na notificação, resumo de
  cada projeção, teto de 200 MB, compartilhar pela tela e leitor do registro para o computador.
- Wi-Fi da moto entrando sozinho, sem a janela de escolha (chave); destino do Waze digitado ou escolhido entre os três
  últimos; gravações de diagnóstico: 50 guardadas, com nomes que não colidem, salvas a cada quadro-chave.

### Alterado
- O painel só é ligado depois que o Android Auto está de pé.
- O centro do recorte e o encaixe novo valem com a projeção ligada; recorte e posição andam de 1 em 1.

### Corrigido
- O Wi-Fi do painel usa agora o nome inteiro da rede (aprendido pela lista de redes ou com o app na tela), em
  vez do começo do nome, que o painel recusava.
- O estado "parado" em que a projeção nasce contava como queda e abria duas janelas de conexão a cada
  início; as falhas de Wi-Fi passam a ser ditas pelo que se sabe.

## 2026-10-03

### Adicionado
- **Modo wallpaper**: manda ao painel uma imagem parada, sem Android Auto.
- Convivência com outro app de painel: o REau percebe quando o Wi-Fi da moto está ocupado.
- Modo dia e noite para o Waze e teto de quadros que desce com o calor do aparelho.
- Manobra e distância do Android Auto no balão do painel (atrás de chave).
- O registro de diagnóstico é aberto a cada projeção, e um travamento do app é gravado antes de o processo morrer, junto
  com o motivo dos últimos fins do processo.
- **Primeiro vídeo no painel de verdade**: autenticação em menos de 0,1 s e imagem a 4 fps com cerca de 160 kbps.

### Alterado
- O encaixe da imagem foi refeito com a tela real do painel: um círculo de centro (263, 263) e raio 263 dentro do
  quadro de 526 por 300, com o computador de bordo abaixo do vídeo; há ajuste de posição da imagem.

## 2026-10-02

### Adicionado
- **Primeira versão do app**: projeto Android com tela inicial, Ajustes guardados, serviço em primeiro plano e máquina
  de estados da projeção.
- **Receptor do Android Auto**: o REau se apresenta ao Android Auto como a central de um carro, pela porta local
  5277, com negociação segura, descoberta de serviços, recepção do vídeo H.264 em pedaços e reconexão.
- **Camada do painel**: pacotes K1G, autenticação, conexão com o painel, Wi-Fi da moto com pareamento guardado cifrado
  e ritmo do vídeo.
- **Vídeo para o painel**: geometria da tela, codificador, compositor com relógio próprio e envio dos pacotes RTP,
  com opção de gravar o vídeo enviado para depuração.
- Registro de diagnóstico por projeção, com gravação da conexão do Android Auto e repetidor para testes.
- O caminho inteiro, do Android Auto ao painel, funcionando dentro do serviço (provado em emulador).
- O projeto passa a se chamar **REau**.
