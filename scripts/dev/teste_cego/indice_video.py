#!/usr/bin/env python3
"""Lê o índice (.csv) de um vídeo H.264 gravado pelo REau (ou extraído de uma .aasession por extrair.py), nos dois formatos.

O .h264 é um fluxo Annex-B: cada quadro vem depois de 4 bytes de código de início (00 00 00 01).
- Formato NOVO (a partir de 03/11/2026; H264Recorder.kt e extrair.py): cabeçalho `frame,pts_ms,bytes_in_file,key`. A coluna é o tamanho do quadro NO ARQUIVO,
  código de início incluído: a soma da coluna é o tamanho do arquivo, e o deslocamento de um quadro é a soma dos anteriores.
- Formato ANTIGO (gravações de 20/10 a 03/11/2026 e o extrair.py antigo): cabeçalho `frame,pts_ms,bytes,key`. A coluna NÃO conta os 4 bytes do código de início:
  soma-se 4 por quadro. (Um .c6.csv do estudo v4/v5 tem este cabeçalho mas já vem somado: a soma da coluna já é o tamanho do arquivo; é reconhecido assim.)
Com o arquivo .h264 à mão, a soma é conferida contra o tamanho dele e o que não bate vira um aviso (`Indice.avisos`), nunca um erro: o índice de uma gravação
cortada ao meio é lido mesmo assim.

Uso como programa:  indice_video.py ARQ.csv [ARQ.h264] [-o SAIDA.csv]
  mostra o formato, os quadros, a soma e o aviso se houver; com -o grava o índice no formato novo (o que o dec5/dec6 e as demais ferramentas leem sem adivinhar).
Uso como módulo:    from indice_video import ler, normalizar
"""
import csv
import os
import sys
from dataclasses import dataclass, field

CABECALHO_NOVO = 'frame,pts_ms,bytes_in_file,key'
CABECALHO_ANTIGO = 'frame,pts_ms,bytes,key'
CODIGO_DE_INICIO = 4

NOVO = 'novo'
ANTIGO = 'antigo'
ANTIGO_JA_SOMADO = 'antigo-ja-somado'


@dataclass
class Indice:
    formato: str
    # (frame, pts_ms, bytes_in_file, key): o tamanho já é o do arquivo, seja qual for o formato lido
    quadros: list
    soma: int
    tamanho_arquivo: 'int | None'
    avisos: list = field(default_factory=list)

    def desloca(self):
        """O deslocamento de cada quadro no .h264."""
        pos, out = 0, []
        for _, _, n, _ in self.quadros:
            out.append(pos)
            pos += n
        return out


def ler(caminho_csv, caminho_h264=None):
    """Lê o índice; devolve um Indice com o tamanho de cada quadro no arquivo (o antigo ganha 4 por quadro)."""
    with open(caminho_csv, newline='') as f:
        linhas = [l for l in csv.reader(f) if l]
    if not linhas:
        raise ValueError(f'{caminho_csv}: índice vazio')
    cab = ','.join(c.strip() for c in linhas[0])
    if cab == CABECALHO_NOVO:
        novo = True
    elif cab == CABECALHO_ANTIGO:
        novo = False
    else:
        raise ValueError(f'{caminho_csv}: cabeçalho desconhecido "{cab}" (esperado "{CABECALHO_NOVO}" ou "{CABECALHO_ANTIGO}")')
    brutos = [(int(a), int(b), int(c), int(d)) for a, b, c, d in (l[:4] for l in linhas[1:])]
    soma_bruta = sum(q[2] for q in brutos)
    tamanho = os.path.getsize(caminho_h264) if caminho_h264 else None
    avisos = []
    formato = NOVO if novo else ANTIGO
    extra = 0 if novo else CODIGO_DE_INICIO
    if not novo and tamanho is not None and soma_bruta + extra * len(brutos) != tamanho and soma_bruta == tamanho:
        formato, extra = ANTIGO_JA_SOMADO, 0
    quadros = [(a, b, c + extra, d) for a, b, c, d in brutos]
    soma = soma_bruta + extra * len(brutos)
    if tamanho is not None and soma != tamanho:
        avisos.append(f'{os.path.basename(caminho_csv)}: formato {formato}, a soma dos {len(quadros)} quadros é {soma} B e o arquivo tem {tamanho} B '
                      f'({tamanho - soma:+d}): os quadros podem ficar desalinhados')
    if formato == ANTIGO_JA_SOMADO:
        avisos.append(f'{os.path.basename(caminho_csv)}: cabeçalho antigo mas a soma já é o tamanho do arquivo: lido como já somado (um .c6.csv?)')
    return Indice(formato, quadros, soma, tamanho, avisos)


def escrever(quadros, caminho):
    """Grava o índice no formato novo (quadros: (frame, pts_ms, bytes_in_file, key))."""
    with open(caminho, 'w') as f:
        f.write(CABECALHO_NOVO + '\n')
        for q in quadros:
            f.write('%d,%d,%d,%d\n' % q)


def normalizar(caminho_csv, caminho_h264, saida):
    """Lê o índice nos dois formatos e o grava no formato novo em [saida]; devolve o Indice (com os avisos)."""
    indice = ler(caminho_csv, caminho_h264)
    escrever(indice.quadros, saida)
    return indice


def main(argv):
    args = list(argv)
    saida = None
    if '-o' in args:
        k = args.index('-o')
        saida = args[k + 1]
        del args[k:k + 2]
    if not 1 <= len(args) <= 2:
        print(__doc__.split('Uso como programa:')[1].split('Uso como módulo')[0].strip(), file=sys.stderr)
        return 2
    indice = ler(args[0], args[1] if len(args) > 1 else None)
    print(f'formato {indice.formato}: {len(indice.quadros)} quadros, soma {indice.soma} B'
          + (f', arquivo {indice.tamanho_arquivo} B' if indice.tamanho_arquivo is not None else ''))
    for a in indice.avisos:
        print('AVISO:', a, file=sys.stderr)
    if saida:
        escrever(indice.quadros, saida)
        print('gravado', saida)
    return 1 if indice.avisos and indice.formato != ANTIGO_JA_SOMADO else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
