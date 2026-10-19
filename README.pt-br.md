# REau

English (padrão): [README.md](README.md)

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

- Mostra no painel a tela do Android Auto, com o enquadramento ajustável (o dos apps e o do menu de apps).
- Manda ao painel a manobra, a distância, a hora de chegada e o tempo restante da rota.
- Manda também a música tocando, as chamadas e o clima, para os cartões do painel.
- Usa o joystick do guidão para comandar o Android Auto (mapa, menu, música, chamadas): tocando, a esquerda atende e a
  direita ou o centro recusam; em ligação, o centro desliga e cima e baixo mudam o volume.
- Fala português e inglês: segue o idioma do celular, e há um seletor nos Ajustes.
- Ajusta a qualidade do vídeo pela distância da próxima manobra e pela velocidade.
- Opcional: mostra o Android Auto num navegador da rede local, protegido por PIN.
- Guarda um registro de diagnóstico no armazenamento interno do app (ligado por padrão, com uma chave nos Ajustes), para
  analisar no computador (`scripts/registro/`); nada sai do celular a não ser que você o compartilhe. Gravar o vídeo e as
  sessões é opcional e vem desligado.

## Requisitos

- Celular com Android 10 ou mais novo e o **Android Auto** instalado.
- O modo de desenvolvedor do Android Auto ligado, com o "servidor da unidade principal". Na edição completa, o REau abre essa tela e
  liga o servidor sozinho quando a acessibilidade do app está ativa; na Lite, você o liga à mão.
- No Android 13 ou mais novo, para ligar o serviço de acessibilidade (edição completa) é preciso antes liberá-lo em
  Configurações › Apps › REau › ⋮ › "Permitir configurações restritas": o Android pede isso de qualquer app instalado
  de fora da Play Store, inclusive o APK publicado.
- Um painel compatível, com o Wi-Fi dele ao alcance.

## Duas edições

- **Completa** (`com.reau.app`): a de sempre. Usa um serviço de acessibilidade do Android para ligar o servidor do Android Auto
  sozinha e para saber qual tela do Android Auto está aparecendo (o encaixe próprio do menu de apps).
- **Lite** (`com.reau.app.lite`, "REau Lite", **a recomendada**): **sem** serviço de acessibilidade. Com o Android Auto 17.4 ou mais novo,
  você liga o servidor da unidade principal à mão (ele desliga quando a sessão termina, então é preciso ligá-lo de novo a cada vez),
  e não há o encaixe próprio do menu de apps.

**Qual usar: a recomendada é a Lite.** Ela não pede o serviço de Acessibilidade: não ganha controle amplo do celular e não briga
com apps que se recusam a rodar com a Acessibilidade ligada (os de banco, por exemplo). O preço é um passo manual a cada uso:
ligar você mesmo o servidor da unidade principal do Android Auto. Escolha a edição completa só se quiser que o REau faça esse
passo sozinho e encaixe o menu de apps do Android Auto.

As duas instalam juntas no mesmo celular, mas não devem rodar ao mesmo tempo (disputam o painel).

## Compilar

Com o JDK 21 e o Android SDK instalados:

```
./gradlew assembleRelease               # os dois APKs: app/build/outputs/apk/completa/release/ e .../lite/release/
./gradlew assembleCompletaRelease       # só a edição completa (com.reau.app); assembleLiteRelease: só a Lite
./gradlew :app:testDebugUnitTest        # testes de unidade
```

Os testes instrumentados (`app/src/androidTest`) rodam num emulador, contra um Android Auto falso e um painel falso.

Há um APK publicado na página de Releases do projeto; para compilar o seu em vez dele, o Gradle precisa achar o Android SDK (`ANDROID_HOME`, ou `sdk.dir` em `local.properties`), e o `assembleRelease` assina com a chave de debug do Android, porque o repositório não traz a chave de assinatura do projeto. As Releases oficiais são assinadas com essa chave, guardada fora do repositório; sem as variáveis de ambiente `REAU_KEYSTORE`, `REAU_KEY_ALIAS`, `REAU_KEYSTORE_PASSWORD` e `REAU_KEY_PASSWORD`, o release compilado localmente sai assinado com a chave de debug.

## Privacidade

O REau não tem conta, anúncio nem coleta de dados. A tela **"O REau e os seus dados"**, no próprio app, diz o que
fica guardado no celular e o que sai dele, com o motivo. O que sai do celular vai para o painel e, só com o servidor de rede
opcional ligado, para outros aparelhos da sua rede local; a única ida à internet é o clima, opcional (Open-Meteo).

## Segurança

Não mexa no celular enquanto pilota. Use o joystick do guidão e só com a moto em condição segura.

## Changelog

Todas as mudanças importantes estão em [CHANGELOG.pt-br.md](CHANGELOG.pt-br.md) (em inglês: [CHANGELOG.md](CHANGELOG.md)).

## Termos de uso

O app pede o aceite dos [termos de uso](TERMS_OF_USE.pt-br.md) na primeira abertura (em inglês: [TERMS_OF_USE.md](TERMS_OF_USE.md)); eles ficam no fim da tela **"O REau e os seus dados"**, junto dos créditos.

## Licença e créditos

O REau é software livre sob a **GNU Affero General Public License v3.0** ([LICENSE](LICENSE)). Ele incorpora código de
outros projetos abertos; o [NOTICE](NOTICE) lista cada um, com a licença e o que foi usado, e o app mostra os mesmos
créditos na tela **Créditos**, no fim de **"O REau e os seus dados"**, onde também está o endereço do código-fonte do
próprio REau (a AGPL pede que quem usa o app possa obtê-lo).
