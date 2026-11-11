# Como contribuir com o REau

English (padrão): [CONTRIBUTING.md](CONTRIBUTING.md) · Español: [CONTRIBUTING.es.md](CONTRIBUTING.es.md)

O REau tem um só mantenedor e é um projeto de horas vagas: não há contrato de suporte nem prazo fixo de resposta. O que
mais ajuda é um relato curto e claro ou uma mudança pequena e testada.

## Licença

O REau é software livre sob a **GNU Affero General Public License v3.0** ([LICENSE](LICENSE)). Tudo o que você contribuir
fica sob a mesma licença. Se uma mudança trouxer código, dados ou fatos de outro projeto, diga de onde vêm, para que o
[NOTICE](NOTICE) e a tela de Créditos (`app/src/main/java/com/reau/app/settings/Credits.kt`) possam citá-lo com a licença dele.

## Como rodar os testes

Com o JDK 21 e o Android SDK instalados, a partir desta pasta:

```
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:detekt :app:lintDebug --console=plain
```

A primeira linha roda os testes de unidade; a segunda roda a análise do código (detekt) e o lint.
Corrija o que eles apontarem no código que você escreveu e não acrescente nada ao baseline do detekt. Um aviso do compilador Kotlin também derruba o build (`allWarningsAsErrors`): corrija-o ou, numa chamada obsoleta que não dá para evitar, ponha ao lado um `@Suppress` que diga o motivo. Alguns testes leem
arquivos do repositório (`docs/`, `NOTICE`, os READMEs, os changelogs, `app/src/main/res/`): o Gradle sabe que eles leem
esses arquivos, então rodam de novo sozinhos quando um texto muda. Os testes instrumentados (`app/src/androidTest`) precisam de um
emulador, como diz o README; os scripts estão em `scripts/emu/`.
Os scripts em Python têm testes próprios (`python3 -B -m unittest discover -p "test_*.py"` dentro de `scripts/`, `scripts/registro/`,
`scripts/dev/`, `scripts/emu/` e `bench/`; o último precisa do Pillow, fixado em `scripts/requirements.txt`), e a
integração contínua também os roda.

Toda mudança de código vem com um teste novo ou ajustado. O projeto tem três idiomas: os textos que as pessoas leem vão em
inglês (o padrão), português (pt-BR) e espanhol, nos `strings.xml` de `values`, `values-pt` e `values-es`, e a documentação tem um arquivo
por idioma. O texto atrás de um (i) dá a ideia geral em no máximo 3 frases e 100 palavras, sem abreviação que termine em ponto
(o `InfoTextsTest` confere nos três idiomas).

## Fuzzing e o teste de mutação

Os leitores de bytes que vêm de fora (os pacotes do painel, os quadros WebSocket e o cabeçalho HTTP de um navegador, e as mensagens, as unidades de vídeo, o cabeçalho de tamanho do vídeo e as mensagens de navegação do Android Auto) têm alvos de fuzzing em
`app/src/test/java/com/reau/app/fuzz/ParserFuzzTargets.kt`, escritos com o [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)
(Apache-2.0, só dependência de teste: nunca entra num APK). No teste unitário normal cada alvo só repete as suas sementes, os arquivos de
`app/src/test/resources/com/reau/app/fuzz/ParserFuzzTargetsInputs/<alvo>/`, como teste de regressão. Para fuzzar de verdade, guiado pelo
código que cada entrada alcança, defina a variável de ambiente (20 segundos por alvo; `JAZZER_MAX_DURATION` muda isso):

```
JAZZER_FUZZ=1 ./gradlew :app:testDebugUnitTest --tests '*ParserFuzzTargets*' --console=plain
```

Com `JAZZER_MAX_DURATION` definida (por exemplo `5m`), só o primeiro alvo da rodada faz fuzz por esse tempo e os outros só repetem as sementes: rode um alvo por vez (`--tests '*ParserFuzzTargets.dashPacket'`). A rodada deixa o corpus de trabalho em `app/.cifuzz-corpus/` e, se uma entrada quebrou um leitor, um arquivo `crash-<hash>` em `app/` (os dois
ficam fora do git). Depois de corrigir o erro, copie esse arquivo para a pasta de sementes do alvo, para que a mesma entrada seja tentada em
toda rodada daí em diante. Um alvo novo é um método com `@FuzzTest` que recebe um `ByteArray`, com poucas sementes pequenas só dele (nunca
capturas reais: veja "Dados pessoais"). Para isso os testes unitários rodam na JUnit Platform (o motor vintage roda os de JUnit 4).

`python3 scripts/dev/mutacoes.py` é o teste de mutação: muda uma decisão do código por vez e confere que um teste unitário falha. `--lista`
conta as mutações e `--fatia I/N` roda uma fatia delas (o workflow semanal roda dez, e o `mutacao-mensal.yml` roda uma de doze por mês, a do número do mês); uma mutação que sobrevive é um teste que não guarda
aquela decisão.

## O que não mudar sem teste na moto

O protocolo do Android Auto e o do painel (`aa/` e `dash/protocol/`), o aperto de mão com o painel e o caminho do vídeo (o
codificador, o compositor, o ritmo dos quadros) só se provam num painel de verdade. Os testes de unidade não substituem
isso: não mude essas partes sem poder dizer o que você experimentou no painel, com qual firmware. O protocolo foi observado
no firmware 11.63 e verificado no 13.10; em outro firmware nada é garantido.

## Dados pessoais

Registros, gravações e fotos podem conter lugares por onde você passou, nomes de Wi-Fi, números de telefone ou nomes de
contatos. Leia-os e corte o que for privado antes de anexá-los a qualquer lugar, e nunca ponha dado pessoal num teste, num
trecho de registro ou numa captura de tela que você vá enviar. Na dúvida, descreva com palavras.

Um problema de segurança não se relata numa issue pública: siga o [SECURITY.md](SECURITY.md).

## Relatar um erro

Use o modelo de relato de erro: ele pede a versão do REau, a versão do Android Auto, o firmware do painel e o
celular, e diz onde o app compartilha os registros.
