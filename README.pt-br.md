# REau

English (padrão): [README.md](README.md) · Español: [README.es.md](README.es.md)

Aplicativo Android que leva o **Android Auto do próprio celular** para o painel TFT da moto, pela rede Wi-Fi do
painel, com a tela do celular apagada. O REau não desenha mapa nenhum: repete no painel o que o app de navegação
(Waze, Google Maps) desenha no Android Auto.

> **Compatibilidade:** funciona com o painel **Tripper Dash**. O REau é um projeto independente, sem ligação com a
> Royal Enfield, e não é endossado nem mantido por ela.

## Como funciona

```
App de navegação ──▶ Android Auto do celular ──(vídeo H.264 por 127.0.0.1)──▶ REau
                                                                              │ decodifica, recorta, reduz
                                                                              ▼
                                                       codificador 526×300 ──RTP──▶ painel da moto
```

O REau se apresenta ao Android Auto como a central de um carro, usando o servidor da unidade principal que o
próprio Android Auto oferece no modo de desenvolvedor. Ele recebe o vídeo, ajusta a imagem à tela do painel e manda
pela rede Wi-Fi do painel.

## O que faz

- Mostra no painel a tela do Android Auto, com o enquadramento ajustável (o dos apps de navegação e o das outras telas,
  menu de apps incluído).
- Manda ao painel a manobra, a distância, a hora de chegada e o tempo restante da rota.
- Manda também a música tocando (quando a página do joystick tem um controle de música), as chamadas e o clima, para os
  cartões do painel.
- Usa o joystick do guidão para comandar o Android Auto (mapa, menu, música, chamadas e Gemini, que vem ligado e passa a funcionar quando você libera o microfone): tocando, a esquerda atende e a
  direita ou o centro recusam; em ligação, o centro desliga e cima e baixo mudam o volume.
- Um anel de informações sobre a imagem, à direita do painel (vem desligado, com uma chave): chuva, vento, tempestade, UV, qualidade do ar, o sol, as temperaturas do dia e a bateria, o sinal e o GPS do celular, uma coisa de cada vez (de fábrica só a chuva, o vento e a trovoada; os outros cartões você liga). Precisa da localização e da internet para o clima.
- Fala português, inglês e espanhol: segue o idioma do celular, e há um seletor nos Ajustes.
- Ajusta a qualidade do vídeo pela distância da próxima manobra e pela velocidade.
- Liga com um toque longo no ícone do REau ("Ligar") ou por um bloco das Configurações rápidas, para ligar em casa, antes do capacete.
- Opcional: mostra o Android Auto num navegador da rede local, protegido por PIN.
- Guarda um registro de diagnóstico no armazenamento interno do app (ligado por padrão, com uma chave nos Ajustes), para
  analisar no computador (`scripts/registro/`); nada sai do celular a não ser que você o compartilhe. Gravar o vídeo e as
  sessões é opcional (Ajustes > Depuração): vem desligado no app que você instala para andar e ligado na build de debug; as gravações ficam
  no celular, saem pelos mesmos dias e pelo mesmo limite de tamanho do registro e guardam o mapa, a rota e as mensagens do Android Auto.

## Requisitos

- Celular com Android 10 ou mais novo e o **Android Auto** instalado.
- O modo de desenvolvedor do Android Auto ligado, com o "servidor da unidade principal". O REau abre essa tela (uma vez cada vez que você liga, com o REau na tela) e você liga o servidor à mão: com o Android Auto 17.4 ou mais novo ele desliga quando a sessão termina, então é preciso ligá-lo de novo a cada vez; com o 17.3 ou mais velho, o REau pede ao Android Auto que se conecte e não há nada para ligar.
- Um painel compatível, com o Wi-Fi dele ao alcance.

## Instalar e atualizar

Instale o APK publicado, `REau-0.3.0.apk` (applicationId `com.reau.app`, nome "REau"). É um app único: o REau reconhece qual tela do Android Auto está aparecendo (mapa, menu, apps) pela própria imagem, com uma rede neural pequena que roda no celular.

Vem da antiga REau Lite (pacote `com.reau.app.lite`)? O REau 0.3.0 é um app novo para você: refaça os ajustes e o pareamento com o painel e depois desinstale a Lite antiga (o app novo mostra uma linha discreta e, se o Android Auto não responder, um aviso, enquanto a Lite antiga ainda estiver instalada). Vem do REau anterior (pacote `com.reau.app`)? É só atualizar por cima: ajustes e pareamento ficam. Duas instalações juntas disputam o único servidor do Android Auto, que aceita uma sessão por vez.

## Compilar

Com o JDK 21 e o Android SDK instalados:

```
./gradlew assembleRelease               # o APK: app/build/outputs/apk/release/
./gradlew assembleDebug                 # o APK de debug: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest        # testes de unidade
```

Os testes instrumentados (`app/src/androidTest`) rodam num emulador, contra um Android Auto falso e um painel falso.

Há um APK publicado na página de Releases do projeto; para compilar o seu em vez dele, o Gradle precisa achar o Android SDK (`ANDROID_HOME`, ou `sdk.dir` em `local.properties`), e, como o repositório não traz a chave de assinatura do projeto, o `assembleRelease` só assina com a chave de debug do Android se você pedir com `-PreauDebugSigned=true`. As Releases oficiais são assinadas com a chave do projeto, guardada fora do repositório; sem as variáveis de ambiente `REAU_KEYSTORE` (o caminho absoluto do arquivo da chave), `REAU_KEY_ALIAS`, `REAU_KEYSTORE_PASSWORD` e `REAU_KEY_PASSWORD`, o empacotamento do release para com um erro.

## Privacidade

O REau não tem conta, anúncio nem coleta de dados. A tela **"O REau e os seus dados"**, no próprio app, diz o que
fica guardado no celular e o que sai dele, com o motivo. O que sai do celular vai para o painel e, só com o servidor de rede
opcional ligado, para outros aparelhos da sua rede local; as únicas idas à internet são o clima (Open-Meteo, para o painel, que vem ligado, ou o anel de informações, que vem desligado: não pede nada até você liberar a localização, e cada um tem uma chave) e o botão “Verificar atualização” em Ajustes, que só pergunta ao GitHub quando você toca. A permissão de localização também deixa o Android dar ao REau a lista de redes Wi-Fi, para ver a do painel, com o app atrás de outro (Android Auto, Waze) ou com a tela apagada; o REau não lê a posição para isso, e o Android pode mostrar o ícone de localização durante a projeção.

## Segurança

Não mexa no celular enquanto pilota. Use o joystick do guidão e só com a moto em condição segura.

## Changelog

Todas as mudanças importantes estão em [CHANGELOG.pt-br.md](CHANGELOG.pt-br.md) (em inglês: [CHANGELOG.md](CHANGELOG.md); em espanhol: [CHANGELOG.es.md](CHANGELOG.es.md)).

## Termos de uso

O app pede o aceite dos [termos de uso](TERMS_OF_USE.pt-br.md) na primeira abertura (em inglês: [TERMS_OF_USE.md](TERMS_OF_USE.md); em espanhol: [TERMS_OF_USE.es.md](TERMS_OF_USE.es.md)); eles ficam no fim da tela **"O REau e os seus dados"**, junto dos créditos.

## Contribuir e segurança

O REau é um projeto de horas vagas, com um só mantenedor. Como relatar um erro, rodar os testes e enviar uma mudança está em [CONTRIBUTING.pt-br.md](CONTRIBUTING.pt-br.md) (em inglês: [CONTRIBUTING.md](CONTRIBUTING.md); em espanhol: [CONTRIBUTING.es.md](CONTRIBUTING.es.md)). Um problema de segurança não se relata numa issue pública: veja o [SECURITY.md](SECURITY.md) (só em inglês).

## Licença e créditos

O REau é software livre sob a **GNU Affero General Public License v3.0** ([LICENSE](LICENSE)). Ele incorpora código de
outros projetos abertos; o [NOTICE](NOTICE) lista cada um, com a licença e o que foi usado, e o app mostra os mesmos
créditos na tela **Créditos**, no fim de **"O REau e os seus dados"**, onde também está o endereço do código-fonte do
próprio REau (a AGPL pede que quem usa o app possa obtê-lo).
